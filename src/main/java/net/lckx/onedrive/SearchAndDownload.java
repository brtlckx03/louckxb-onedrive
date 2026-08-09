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
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Browses a specific folder in a OneDrive account using the Microsoft Graph API.
 * Authenticates via the OAuth 2.0 device code flow with token caching so you
 * only need to sign in once (refresh token is stored in ~/.onedrive-token).
 * <p>
 * User: louckxb, Date: 29/03/2026.
 */
public class SearchAndDownload {

    private static final String CLIENT_ID = "14d82eec-204b-4c2f-b7e8-296a70dab67e";
    private static final String AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String DEFAULT_FOLDER = "2025";
    private static final Path TOKEN_FILE = Path.of(System.getProperty("user.home"), ".onedrive-token");
    private static final Path DOWNLOAD_BASE = Path.of(System.getProperty("user.home"), "Downloads", "OneDrive");

    void main() throws Exception {
        var client = trustAllHttpClient();
        var scanner = new Scanner(System.in);

        // Step 1: Authenticate (try cached token first, fall back to device code flow)
        String accessToken = authenticate(client);
        if (accessToken == null) return;

        // Step 2: Ask for the starting folder
        System.out.printf("Which folder do you want to browse? [%s]: ", DEFAULT_FOLDER);
        String input = scanner.nextLine().trim();
        String currentPath = input.isEmpty() ? DEFAULT_FOLDER : input;

        // Step 3: Interactive browsing loop
        List<String[]> currentItems = listFolder(client, accessToken, currentPath);
        if (currentItems == null) return;

        while (true) {
            System.out.println();
            System.out.println("📂 " + (currentPath.isEmpty() ? "/" : currentPath));
            System.out.println("-".repeat(50));
            if (!currentPath.isEmpty()) {
                System.out.println("  ..                (go back)");
            }
            System.out.println("  d                 (download this folder)");
            System.out.println("  q                 (quit)");
            System.out.printf("%nEnter folder name or number to open: ");

            String choice = scanner.nextLine().trim();

            if (choice.equalsIgnoreCase("q") || choice.isEmpty()) {
                System.out.println("Bye!");
                return;
            }

            if (choice.equalsIgnoreCase("d")) {
                Path downloadDir = DOWNLOAD_BASE.resolve(currentPath);
                System.out.printf("Download to: %s%n", downloadDir);
                System.out.println("  1. Quick start  (count files per folder as we go)");
                System.out.println("  2. Full count   (count everything upfront, then download)");
                System.out.print("Choose mode [1]: ");
                String modeInput = scanner.nextLine().trim();
                boolean fullCount = modeInput.equals("2");

                System.out.print("Proceed? [Y/n]: ");
                String confirm = scanner.nextLine().trim();
                if (confirm.isEmpty() || confirm.equalsIgnoreCase("y")) {
                    long startTime = System.currentTimeMillis();
                    if (fullCount) {
                        // Mode 2: count all items upfront, then download with global totals
                        // counters: [0]=folders done, [1]=total folders, [2]=files done, [3]=total files
                        int[] counters = {0, 0, 0, 0};
                        System.out.print("  Counting items...");
                        countItems(client, accessToken, currentPath, counters);
                        System.out.printf("\r  Found %d folders, %d files — starting download%n",
                                counters[1], counters[3]);
                        counters[0] = 0; counters[2] = 0;
                        downloadFolderFull(client, accessToken, currentPath, downloadDir, startTime, counters);
                        long elapsed = System.currentTimeMillis() - startTime;
                        System.out.printf("%n  ✅ Download complete — %d/%d files in %s%n",
                                counters[2], counters[3], formatDuration(elapsed));
                    } else {
                        // Mode 1: no upfront count, show file X/Y per folder, running folder counter
                        // counters: [0]=folders visited, [1]=total files downloaded
                        int[] counters = {0, 0};
                        downloadFolder(client, accessToken, currentPath, downloadDir, startTime, counters);
                        long elapsed = System.currentTimeMillis() - startTime;
                        System.out.printf("%n  ✅ Download complete — %d files in %s%n",
                                counters[1], formatDuration(elapsed));
                    }
                }
                continue;
            }

            String newPath;
            if (choice.equals("..")) {
                int lastSlash = currentPath.lastIndexOf('/');
                newPath = lastSlash > 0 ? currentPath.substring(0, lastSlash) : "";
            } else {
                // Allow entering a number to pick a folder, but only if
                // it's in the valid range — otherwise treat it as a folder name
                String selectedFolder = choice;
                List<String> folderNames = currentItems.stream()
                        .filter(f -> f[2].equals("folder"))
                        .map(f -> f[0])
                        .toList();
                try {
                    int idx = Integer.parseInt(choice);
                    if (idx >= 1 && idx <= folderNames.size()) {
                        selectedFolder = folderNames.get(idx - 1);
                    }
                } catch (NumberFormatException ignored) {
                }
                newPath = currentPath.isEmpty() ? selectedFolder : currentPath + "/" + selectedFolder;
            }

            List<String[]> newItems = listFolder(client, accessToken, newPath);
            if (newItems != null) {
                currentPath = newPath;
                currentItems = newItems;
            }
            // On error, stay on current folder — listFolder already printed the message
        }
    }

    /**
     * Lists the contents of a folder and prints folders and files.
     * Returns the list of items (each as [name, detail, type]) or null on error.
     */
    List<String[]> listFolder(HttpClient client, String accessToken, String path) throws Exception {
        String url;
        if (path.isEmpty()) {
            url = GRAPH_BASE + "/me/drive/root/children";
        } else {
            String encodedPath = encodePath(path);
            url = GRAPH_BASE + "/me/drive/root:/" + encodedPath + ":/children";
        }

        List<String> items = fetchAllItems(client, url, accessToken);
        if (items == null) {
            System.out.println("❌ Folder not found: \"" + path + "\"");
            return null;
        }
        List<String[]> allItems = new ArrayList<>();

        List<String[]> folders = new ArrayList<>();
        List<String[]> files = new ArrayList<>();

        for (String item : items) {
            String name = jsonString(item, "name");
            if (item.contains("\"folder\"")) {
                String childCount = jsonInt(item, "childCount") + " items";
                folders.add(new String[]{name, childCount});
                allItems.add(new String[]{name, childCount, "folder"});
            } else {
                long size = jsonLong(item, "size");
                files.add(new String[]{name, formatSize(size)});
                allItems.add(new String[]{name, formatSize(size), "file"});
            }
        }

        System.out.println("\n=== FOLDERS (" + folders.size() + ") ===");
        int idx = 1;
        for (String[] folder : folders) {
            System.out.printf("  %2d. 📁 %-48s (%s)%n", idx++, folder[0], folder[1]);
        }
        if (folders.isEmpty()) System.out.println("  (no folders found)");

        System.out.println("\n=== FILES (" + files.size() + ") ===");
        for (String[] file : files) {
            System.out.printf("      📄 %-48s (%s)%n", file[0], file[1]);
        }
        if (files.isEmpty()) System.out.println("  (no files found)");

        return allItems;
    }

    /** URL-encodes each path segment individually, preserving '/' as separator. */
    String encodePath(String path) {
        String[] segments = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    // --- Download ---

    /** Recursively counts all folders and files for mode 2 (full count upfront). */
    void countItems(HttpClient client, String accessToken, String remotePath, int[] counters) throws Exception {
        String url = remotePath.isEmpty()
                ? GRAPH_BASE + "/me/drive/root/children"
                : GRAPH_BASE + "/me/drive/root:/" + encodePath(remotePath) + ":/children";

        List<String> items = fetchAllItems(client, url, accessToken);
        if (items == null) return;

        for (String item : items) {
            if (item.contains("\"folder\"")) {
                counters[1]++; // total folders
                String name = jsonString(item, "name");
                String subPath = remotePath.isEmpty() ? name : remotePath + "/" + name;
                System.out.printf("\r  Counting... %d folders, %d files found", counters[1], counters[3]);
                if (jsonInt(item, "childCount") > 0) {
                    countItems(client, accessToken, subPath, counters);
                }
            } else {
                counters[3]++; // total files
            }
        }
    }

    /** Mode 2: download with global file/folder counters (totals known upfront). */
    void downloadFolderFull(HttpClient client, String accessToken, String remotePath, Path localDir,
                            long startTime, int[] counters) throws Exception {
        Files.createDirectories(localDir);

        String url = remotePath.isEmpty()
                ? GRAPH_BASE + "/me/drive/root/children"
                : GRAPH_BASE + "/me/drive/root:/" + encodePath(remotePath) + ":/children";

        List<String> items = fetchAllItems(client, url, accessToken);
        if (items == null) { System.out.println("  ❌ Could not list: " + remotePath); return; }

        for (String item : items) {
            String name = jsonString(item, "name");
            if (item.contains("\"folder\"")) {
                counters[0]++;
                String subPath = remotePath.isEmpty() ? name : remotePath + "/" + name;
                long elapsed = System.currentTimeMillis() - startTime;
                System.out.printf("  📁 [folder %d/%d] %s  [%s elapsed]%n",
                        counters[0], counters[1], name, formatDuration(elapsed));
                downloadFolderFull(client, accessToken, subPath, localDir.resolve(name), startTime, counters);
            } else {
                counters[2]++;
                long elapsed = System.currentTimeMillis() - startTime;
                Path targetFile = localDir.resolve(name);
                if (Files.exists(targetFile) && Files.size(targetFile) == jsonLong(item, "size")) {
                    System.out.printf("  ⏭️  [file %d/%d] %s (already exists)  [%s elapsed]%n",
                            counters[2], counters[3], name, formatDuration(elapsed));
                    continue;
                }
                System.out.printf("  ⬇️  [file %d/%d] %s (%s)  [%s elapsed]",
                        counters[2], counters[3], name, formatSize(jsonLong(item, "size")), formatDuration(elapsed));
                try { downloadFile(client, getDownloadUrl(item), targetFile, accessToken); System.out.println(" ✓"); }
                catch (Exception e) { System.out.println(" ❌ " + e.getMessage()); }
            }
        }
    }

    /** Mode 1: download without upfront count — file X/Y per folder, running folder counter. */
    void downloadFolder(HttpClient client, String accessToken, String remotePath, Path localDir,
                        long startTime, int[] counters) throws Exception {
        Files.createDirectories(localDir);

        String url = remotePath.isEmpty()
                ? GRAPH_BASE + "/me/drive/root/children"
                : GRAPH_BASE + "/me/drive/root:/" + encodePath(remotePath) + ":/children";

        List<String> items = fetchAllItems(client, url, accessToken);
        if (items == null) {
            System.out.println("  ❌ Could not list: " + remotePath);
            return;
        }

        // Count files in this folder from the listing we already have — no extra API call needed
        int filesInFolder = (int) items.stream().filter(i -> !i.contains("\"folder\"")).count();
        int fileInFolderDone = 0;

        for (String item : items) {
            String name = jsonString(item, "name");

            if (item.contains("\"folder\"")) {
                counters[0]++;
                String subPath = remotePath.isEmpty() ? name : remotePath + "/" + name;
                long elapsed = System.currentTimeMillis() - startTime;
                System.out.printf("  📁 [folder %d] %s  [%s elapsed]%n",
                        counters[0], name, formatDuration(elapsed));
                downloadFolder(client, accessToken, subPath, localDir.resolve(name), startTime, counters);
            } else {
                fileInFolderDone++;
                counters[1]++;
                Path targetFile = localDir.resolve(name);
                long elapsed = System.currentTimeMillis() - startTime;
                if (Files.exists(targetFile) && Files.size(targetFile) == jsonLong(item, "size")) {
                    System.out.printf("  ⏭️  [file %d/%d] %s (already exists)  [%s elapsed]%n",
                            fileInFolderDone, filesInFolder, name, formatDuration(elapsed));
                    continue;
                }

                System.out.printf("  ⬇️  [file %d/%d] %s (%s)  [%s elapsed]",
                        fileInFolderDone, filesInFolder, name,
                        formatSize(jsonLong(item, "size")), formatDuration(elapsed));

                try {
                    downloadFile(client, getDownloadUrl(item), targetFile, accessToken);
                    System.out.println(" ✓");
                } catch (Exception e) {
                    System.out.println(" ❌ " + e.getMessage());
                }
            }
        }
    }

    /** Returns the download URL for a Graph API item JSON object. */
    String getDownloadUrl(String item) {
        String url = jsonString(item, "@microsoft.graph.downloadUrl");
        if (url.isEmpty()) {
            url = GRAPH_BASE + "/me/drive/items/" + jsonString(item, "id") + "/content";
        }
        return url;
    }

    /** Downloads a single file from a URL to a local path. */
    void downloadFile(HttpClient client, String url, Path target, String accessToken) throws Exception {
        var builder = HttpRequest.newBuilder().uri(URI.create(url));

        if (url.contains("graph.microsoft.com")) {
            builder.header("Authorization", "Bearer " + accessToken);
        }

        var request = builder.GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() == 302) {
            String redirect = response.headers().firstValue("Location").orElse("");
            if (!redirect.isEmpty()) {
                var redirectRequest = HttpRequest.newBuilder().uri(URI.create(redirect)).GET().build();
                response = client.send(redirectRequest, HttpResponse.BodyHandlers.ofInputStream());
            }
        }

        try (InputStream in = response.body();
             var out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
    }

    // --- Authentication with token caching ---

    String authenticate(HttpClient client) throws Exception {
        // Try to reuse a cached refresh token
        if (Files.exists(TOKEN_FILE)) {
            String refreshToken = Files.readString(TOKEN_FILE).trim();
            System.out.print("Refreshing cached token...");

            String tokenResponse = post(client, AUTHORITY + "/token",
                    Map.of("client_id", CLIENT_ID,
                            "grant_type", "refresh_token",
                            "refresh_token", refreshToken,
                            "scope", "Files.Read offline_access"));

            if (tokenResponse.contains("\"access_token\"")) {
                // Save the new refresh token (they rotate)
                saveRefreshToken(tokenResponse);
                System.out.println(" ✓ Session restored!\n");
                return jsonString(tokenResponse, "access_token");
            } else {
                System.out.println(" expired, re-authenticating...\n");
                Files.deleteIfExists(TOKEN_FILE);
            }
        }

        // Device code flow (first time or after token expiry)
        String deviceResponse = post(client, AUTHORITY + "/devicecode",
                Map.of("client_id", CLIENT_ID, "scope", "Files.Read offline_access"));

        String userCode = jsonString(deviceResponse, "user_code");
        String deviceCode = jsonString(deviceResponse, "device_code");
        int interval = jsonInt(deviceResponse, "interval");

        System.out.println("=".repeat(50));
        System.out.println("To sign in, open:  https://www.microsoft.com/link");
        System.out.println("Enter the code:    " + userCode);
        System.out.println("=".repeat(50));
        System.out.print("Waiting for authentication");

        while (true) {
            Thread.sleep(interval * 1000L);
            String tokenResponse = post(client, AUTHORITY + "/token",
                    Map.of("client_id", CLIENT_ID,
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                            "device_code", deviceCode));

            if (tokenResponse.contains("\"access_token\"")) {
                saveRefreshToken(tokenResponse);
                System.out.println("\n✓ Authenticated successfully!\n");
                return jsonString(tokenResponse, "access_token");
            } else if (tokenResponse.contains("authorization_pending")) {
                System.out.print(".");
            } else {
                System.out.println("\nAuthentication failed: " + jsonString(tokenResponse, "error_description"));
                return null;
            }
        }
    }

    void saveRefreshToken(String tokenResponse) throws IOException {
        String refreshToken = jsonString(tokenResponse, "refresh_token");
        if (!refreshToken.isEmpty()) {
            Files.writeString(TOKEN_FILE, refreshToken);
        }
    }

    // --- HTTP helpers ---

    /** Fetches all paginated items from a Graph API list URL. Returns null if the first response is an error. */
    List<String> fetchAllItems(HttpClient client, String firstUrl, String accessToken) throws Exception {
        List<String> all = new ArrayList<>();
        String nextUrl = firstUrl;
        while (nextUrl != null && !nextUrl.isEmpty()) {
            String response = get(client, nextUrl, accessToken);
            if (response.contains("\"error\"")) return null;
            all.addAll(jsonArray(response, "value"));
            nextUrl = jsonString(response, "@odata.nextLink");
        }
        return all;
    }

    /** Returns an HttpClient that trusts all certificates (needed for Microsoft CDN download URLs). */
    HttpClient trustAllHttpClient() throws Exception {
        var trustAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{trustAll}, new SecureRandom());
        return HttpClient.newBuilder().sslContext(sslContext).build();
    }


    String post(HttpClient client, String url, Map<String, String> formParams) throws Exception {
        // Use LinkedHashMap to guarantee iteration order for predictable encoding
        var ordered = new LinkedHashMap<>(formParams);
        String body = ordered.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");

        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    String get(HttpClient client, String url, String bearerToken) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    // --- JSON helpers (minimal, no external dependencies) ---

    String jsonString(String json, String key) {
        var pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        var matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    int jsonInt(String json, String key) {
        var pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        var matcher = pattern.matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    long jsonLong(String json, String key) {
        var pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        var matcher = pattern.matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    /**
     * Extracts top-level objects from a JSON array value.
     * Properly tracks brace depth and skips over string literals to handle
     * braces inside JSON string values.
     */
    List<String> jsonArray(String json, String key) {
        List<String> items = new ArrayList<>();
        int arrayStart = json.indexOf("\"" + key + "\"");
        if (arrayStart == -1) return items;

        int bracketStart = json.indexOf('[', arrayStart);
        if (bracketStart == -1) return items;

        int depth = 1; // we start inside the '[' bracket
        int itemStart = -1;
        boolean inString = false;
        boolean escaped = false;

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
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) break;
            }
        }
        return items;
    }

    String formatSize(long bytes) {
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

}
