package net.lckx.onedrive;


import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;

/**
 * Uploads a local folder to OneDrive using the Microsoft Graph API.
 * Authenticates via the OAuth 2.0 device code flow with token caching.
 * Uses a separate token (~/.onedrive-rw-token) because upload requires
 * Files.ReadWrite scope instead of the read-only Files.Read scope.
 * <p>
 * Small files (< 4 MB) are uploaded directly with a PUT request.
 * Large files (>= 4 MB) use the resumable upload session API with
 * 5 MB chunks and live progress percentage.
 * <p>
 * User: louckxb, Date: 29/03/2026.
 */
public class Upload {

    private static final String CLIENT_ID = "14d82eec-204b-4c2f-b7e8-296a70dab67e";
    private static final String AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    // Separate token file: upload needs Files.ReadWrite, not just Files.Read
    private static final Path TOKEN_FILE = Path.of(System.getProperty("user.home"), ".onedrive-rw-token");
    // 5 MB chunks — must be a multiple of 320 KiB per Graph API requirement
    private static final int CHUNK_SIZE = 5 * 1024 * 1024;
    private static final long SMALL_FILE = 4 * 1024 * 1024; // files below this use simple PUT
    private static final Path DEFAULT_CAMERA = Path.of("/Users/louckxb/Downloads/Camera");
    private static final Path ARCHIVE_BASE = Path.of("/Users/louckxb/Downloads/OneDrive");

    void main() throws Exception {
        var client = HttpClient.newHttpClient();
        var scanner = new Scanner(System.in);

        // Step 1: Authenticate with read-write scope
        String accessToken = authenticate(client);
        if (accessToken == null) return;

        // Step 2+: Loop — ask for a folder, upload, then ask again
        while (true) {
            // Refresh token before each upload to avoid expiry mid-session
            accessToken = authenticate(client);
            if (accessToken == null) return;

            System.out.printf("Local folder to upload [%s]: ", DEFAULT_CAMERA);
            String localInput = unquote(scanner.nextLine().trim());
            if (localInput.equalsIgnoreCase("q") || localInput.equalsIgnoreCase("quit")) break;
            Path localFolder = localInput.isEmpty() ? DEFAULT_CAMERA :
                    Path.of(localInput.contains("/") ? expandHome(localInput) : DEFAULT_CAMERA + "/" + localInput);
            if (!Files.isDirectory(localFolder)) {
                System.out.println("❌ Not a valid local folder: " + localFolder);
                continue;
            }

            // Ask for OneDrive destination
            String folderName = localFolder.getFileName().toString();
            String prefix = folderName.length() >= 4 ? folderName.substring(0, 4) : "";
            String suggestedRemote = prefix.matches("\\d{4}") ? prefix + "/" + folderName : folderName;
            System.out.printf("OneDrive destination [%s]: ", suggestedRemote);
            String remoteInput = unquote(scanner.nextLine().trim());
            String remotePath = remoteInput.isEmpty() ? suggestedRemote : remoteInput;

            // Count files to upload
            System.out.print("  Counting files...");
            List<Path> allFiles = new ArrayList<>();
            collectFiles(localFolder, allFiles);
            System.out.printf("\r  Found %d files to upload%n", allFiles.size());

            System.out.printf("Upload \"%s\" → OneDrive: \"%s\"%n", localFolder, remotePath);
            System.out.print("Proceed? [Y/n]: ");
            String confirm = scanner.nextLine().trim();
            if (!confirm.isEmpty() && !confirm.equalsIgnoreCase("y")) continue;

            // Upload
            long startTime = System.currentTimeMillis();
            int[] counters = {0, allFiles.size()}; // [done, total]
            long[] totalBytes = {0};
            uploadFolder(client, accessToken, localFolder, remotePath, startTime, counters, totalBytes);

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("%n  ✅ Upload complete — %d files (%s) in %s%n",
                    counters[0], formatSize(totalBytes[0]), formatDuration(elapsed));

            // Move the uploaded folder to the local archive
            String folderNameStr = localFolder.getFileName().toString();
            String yearPrefix = folderNameStr.length() >= 4 ? folderNameStr.substring(0, 4) : "";
            Path archiveDir = yearPrefix.matches("\\d{4}") ? ARCHIVE_BASE.resolve(yearPrefix) : ARCHIVE_BASE;
            Path archiveTarget = archiveDir.resolve(localFolder.getFileName());
            Files.createDirectories(archiveDir);
            if (Files.exists(archiveTarget)) {
                System.out.printf("  ⚠️  Archive target already exists, skipping move: %s%n", archiveTarget);
            } else {
                Files.move(localFolder, archiveTarget);
                System.out.printf("  📦 Moved to: %s%n", archiveTarget);
            }

            System.out.println();
        }
    }

    // --- Upload logic ---

    /**
     * Recursively collects all files under a directory.
     */
    void collectFiles(Path dir, List<Path> result) throws IOException {
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.sorted().toList()) {
                if (Files.isDirectory(entry)) collectFiles(entry, result);
                else result.add(entry);
            }
        }
    }

    /**
     * Recursively uploads a local directory to a OneDrive path.
     */
    void uploadFolder(HttpClient client, String accessToken, Path localDir,
                      String remotePath, long startTime, int[] counters, long[] totalBytes) throws Exception {

        ensureFolder(client, accessToken, remotePath);

        try (var stream = Files.list(localDir)) {
            for (Path entry : stream.sorted().toList()) {
                String name = entry.getFileName().toString();
                String entryRemotePath = remotePath + "/" + name;

                if (Files.isDirectory(entry)) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    System.out.printf("  📁 %s  [%s elapsed]%n", entryRemotePath, formatDuration(elapsed));
                    uploadFolder(client, accessToken, entry, entryRemotePath, startTime, counters, totalBytes);
                } else {
                    counters[0]++;
                    long fileSize = Files.size(entry);
                    long elapsed = System.currentTimeMillis() - startTime;

                    // Skip if already exists on OneDrive with the same size
                    if (remoteFileExists(client, accessToken, entryRemotePath, fileSize)) {
                        System.out.printf("  ⏭️  [%d/%d] %s (already exists)  [%s elapsed]%n",
                                counters[0], counters[1], name, formatDuration(elapsed));
                        continue;
                    }

                    System.out.printf("  ⬆️  [%d/%d] %s (%s)  [%s elapsed]",
                            counters[0], counters[1], name, formatSize(fileSize), formatDuration(elapsed));

                    try {
                        if (fileSize < SMALL_FILE) {
                            uploadSmall(client, accessToken, entry, entryRemotePath);
                        } else {
                            uploadLarge(client, accessToken, entry, entryRemotePath, fileSize, startTime, counters);
                        }
                        totalBytes[0] += fileSize;
                        System.out.println(" ✓");
                    } catch (Exception e) {
                        System.out.println(" ❌ " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Creates a OneDrive folder (and all parents) if it doesn't already exist.
     */
    void ensureFolder(HttpClient client, String accessToken, String remotePath) throws Exception {
        String[] segments = remotePath.split("/");
        StringBuilder current = new StringBuilder();
        for (String seg : segments) {
            if (!current.isEmpty()) current.append('/');
            current.append(seg);
            String path = current.toString();

            // Check if it exists already
            String checkUrl = GRAPH_BASE + "/me/drive/root:/" + encodePath(path);
            String checkResp = get(client, checkUrl, accessToken);
            if (!checkResp.contains("\"error\"")) continue; // already exists

            // Create it
            String parentPath = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
            String createUrl = parentPath.isEmpty()
                    ? GRAPH_BASE + "/me/drive/root/children"
                    : GRAPH_BASE + "/me/drive/root:/" + encodePath(parentPath) + ":/children";
            String body = "{\"name\":\"" + seg.replace("\"", "\\\"") + "\",\"folder\":{},\"@microsoft.graph.conflictBehavior\":\"rename\"}";
            String createResp = postJson(client, createUrl, body, accessToken);
            if (createResp.contains("\"error\"")) {
                System.out.println("  ⚠️  Could not create folder: " + path);
            }
        }
    }

    /**
     * Returns true if a file at the given remote path already has the expected size.
     */
    boolean remoteFileExists(HttpClient client, String accessToken, String remotePath, long expectedSize) throws Exception {
        String url = GRAPH_BASE + "/me/drive/root:/" + encodePath(remotePath);
        String resp = get(client, url, accessToken);
        if (resp.contains("\"error\"")) return false;
        long remoteSize = jsonLong(resp, "size");
        return remoteSize == expectedSize;
    }

    /**
     * Uploads a file smaller than 4 MB using a simple PUT request.
     */
    void uploadSmall(HttpClient client, String accessToken, Path file, String remotePath) throws Exception {
        String url = GRAPH_BASE + "/me/drive/root:/" + encodePath(remotePath) + ":/content";
        byte[] data = Files.readAllBytes(file);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();
        String resp = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        if (resp.contains("\"error\"")) throw new Exception(jsonString(resp, "message"));
    }

    /**
     * Uploads a large file using the resumable upload session API with progress.
     */
    void uploadLarge(HttpClient client, String accessToken, Path file,
                     String remotePath, long fileSize, long startTime, int[] counters) throws Exception {

        // Create upload session
        String sessionUrl = GRAPH_BASE + "/me/drive/root:/" + encodePath(remotePath) + ":/createUploadSession";
        String sessionBody = "{\"item\":{\"@microsoft.graph.conflictBehavior\":\"replace\"}}";
        String sessionResp = postJson(client, sessionUrl, sessionBody, accessToken);
        if (sessionResp.contains("\"error\"")) throw new Exception(jsonString(sessionResp, "message"));
        String uploadUrl = jsonString(sessionResp, "uploadUrl");

        // Upload in chunks
        try (InputStream in = Files.newInputStream(file)) {
            long uploaded = 0;
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                long chunkEnd = uploaded + bytesRead - 1;
                byte[] chunk = bytesRead == buffer.length ? buffer : Arrays.copyOf(buffer, bytesRead);

                var chunkRequest = HttpRequest.newBuilder()
                        .uri(URI.create(uploadUrl))
                        .header("Content-Range", String.format("bytes %d-%d/%d", uploaded, chunkEnd, fileSize))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                        .build();

                String chunkResp = client.send(chunkRequest, HttpResponse.BodyHandlers.ofString()).body();
                if (chunkResp.contains("\"error\"")) throw new Exception(jsonString(chunkResp, "message"));

                uploaded += bytesRead;
                int pct = (int) (uploaded * 100 / fileSize);
                long elapsed = System.currentTimeMillis() - startTime;
                System.out.printf("\r  ⬆️  [%d/%d] %s (%s) — %d%% DONE  [%s elapsed]",
                        counters[0], counters[1], file.getFileName(), formatSize(fileSize), pct, formatDuration(elapsed));
            }
        }
    }

    // --- Helpers ---

    /**
     * URL-encodes each path segment individually, preserving '/' as separator.
     */
    String encodePath(String path) {
        String[] segments = path.split("/");
        var sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    String formatDuration(long millis) {
        long secs = millis / 1000;
        long mins = secs / 60;
        long hrs = mins / 60;
        if (hrs > 0) return String.format("%dh %02dm %02ds", hrs, mins % 60, secs % 60);
        if (mins > 0) return String.format("%dm %02ds", mins, secs % 60);
        return String.format("%ds", secs);
    }

    // --- Authentication (read-write scope) ---

    String authenticate(HttpClient client) throws Exception {
        if (Files.exists(TOKEN_FILE)) {
            String refreshToken = Files.readString(TOKEN_FILE).trim();
            System.out.print("Refreshing cached token...");
            String tokenResp = post(client, AUTHORITY + "/token",
                    Map.of("client_id", CLIENT_ID, "grant_type", "refresh_token",
                            "refresh_token", refreshToken, "scope", "Files.ReadWrite offline_access"));
            if (tokenResp.contains("\"access_token\"")) {
                saveRefreshToken(tokenResp);
                System.out.println(" ✓ Session restored!\n");
                return jsonString(tokenResp, "access_token");
            }
            System.out.println(" expired, re-authenticating...\n");
            Files.deleteIfExists(TOKEN_FILE);
        }

        String deviceResp = post(client, AUTHORITY + "/devicecode",
                Map.of("client_id", CLIENT_ID, "scope", "Files.ReadWrite offline_access"));
        String userCode = jsonString(deviceResp, "user_code");
        String deviceCode = jsonString(deviceResp, "device_code");
        int interval = jsonInt(deviceResp, "interval");

        System.out.println("=".repeat(50));
        System.out.println("To sign in, open:  https://www.microsoft.com/link");
        System.out.println("Enter the code:    " + userCode);
        System.out.println("=".repeat(50));
        System.out.print("Waiting for authentication");

        while (true) {
            Thread.sleep(interval * 1000L);
            String tokenResp = post(client, AUTHORITY + "/token",
                    Map.of("client_id", CLIENT_ID,
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                            "device_code", deviceCode));
            if (tokenResp.contains("\"access_token\"")) {
                saveRefreshToken(tokenResp);
                System.out.println("\n✓ Authenticated successfully!\n");
                return jsonString(tokenResp, "access_token");
            } else if (tokenResp.contains("authorization_pending")) {
                System.out.print(".");
            } else {
                System.out.println("\nAuthentication failed: " + jsonString(tokenResp, "error_description"));
                return null;
            }
        }
    }

    void saveRefreshToken(String tokenResp) throws IOException {
        String refreshToken = jsonString(tokenResp, "refresh_token");
        if (!refreshToken.isEmpty()) Files.writeString(TOKEN_FILE, refreshToken);
    }

    // --- HTTP helpers ---

    String post(HttpClient client, String url, Map<String, String> formParams) throws Exception {
        var ordered = new LinkedHashMap<>(formParams);
        String body = ordered.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        var request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    String postJson(HttpClient client, String url, String jsonBody, String accessToken) throws Exception {
        var request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    String get(HttpClient client, String url, String bearerToken) throws Exception {
        var request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", "Bearer " + bearerToken).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    // --- JSON helpers ---

    String jsonString(String json, String key) {
        var m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    int jsonInt(String json, String key) {
        var m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    long jsonLong(String json, String key) {
        var m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    List<String> jsonArray(String json, String key) {
        List<String> items = new ArrayList<>();
        int arrayStart = json.indexOf("\"" + key + "\"");
        if (arrayStart == -1) return items;
        int bracketStart = json.indexOf('[', arrayStart);
        if (bracketStart == -1) return items;

        int depth = 1, itemStart = -1;
        boolean inString = false, escaped = false;
        for (int i = bracketStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') {
                if (depth == 1 && itemStart == -1) itemStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 1 && itemStart != -1) {
                    items.add(json.substring(itemStart, i + 1));
                    itemStart = -1;
                }
            } else if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) break;
            }
        }
        return items;
    }

    private static String unquote(String input) {
        if (input.length() >= 2
                && ((input.startsWith("\"") && input.endsWith("\""))
                    || (input.startsWith("'") && input.endsWith("'")))) {
            return input.substring(1, input.length() - 1).trim();
        }
        return input;
    }

    private static String expandHome(String value) {
        if (value.equals("~")) return System.getProperty("user.home");
        if (value.startsWith("~/")) return System.getProperty("user.home") + value.substring(1);
        return value;
    }
}
