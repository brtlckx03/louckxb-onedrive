/**
 * Scans all folders in a OneDrive account and displays the top 200 largest
 * folders by total size. Uses the same authentication as Main.java
 * (device code flow with token caching in ~/.onedrive-token).
 *
 * User: louckxb, Date: 29/03/2026.
 */

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

private static final String CLIENT_ID = "14d82eec-204b-4c2f-b7e8-296a70dab67e";
private static final String AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
private static final Path TOKEN_FILE = Path.of(System.getProperty("user.home"), ".onedrive-token");
private static final int TOP_N = 200;

void main() throws Exception {
    var client = HttpClient.newHttpClient();

    // Step 1: Authenticate
    String accessToken = authenticate(client);
    if (accessToken == null) return;

    // Step 2: Recursively scan all folders
    System.out.println("Scanning all folders in OneDrive...\n");
    List<long[]> folderSizes = new ArrayList<>(); // parallel list for sizes
    List<String> folderPaths = new ArrayList<>();
    scanFolder(client, accessToken, "", folderPaths, folderSizes);

    System.out.printf("\n\nScan complete: %d folders found.%n%n", folderPaths.size());

    // Step 3: Sort by size descending and display top N
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < folderPaths.size(); i++) indices.add(i);
    indices.sort((a, b) -> Long.compare(folderSizes.get(b)[0], folderSizes.get(a)[0]));

    int limit = Math.min(TOP_N, indices.size());
    System.out.println("=".repeat(90));
    System.out.printf(" TOP %d LARGEST FOLDERS%n", limit);
    System.out.println("=".repeat(90));
    System.out.printf(" %4s  %-12s  %-6s  %s%n", "Rank", "Size", "Items", "Folder path");
    System.out.println("-".repeat(90));

    for (int rank = 0; rank < limit; rank++) {
        int idx = indices.get(rank);
        String path = folderPaths.get(idx);
        long size = folderSizes.get(idx)[0];
        int childCount = (int) folderSizes.get(idx)[1];
        System.out.printf(" %4d. %-12s  %5d   📁 %s%n",
                rank + 1, formatSize(size), childCount,
                path.isEmpty() ? "/" : path);
    }
    System.out.println("=".repeat(90));

    // Show total
    long totalBytes = folderSizes.stream().mapToLong(s -> s[0]).max().orElse(0);
    System.out.printf("%nTotal OneDrive usage: %s%n", formatSize(totalBytes));
}

/** Recursively scans a folder, collecting all subfolder paths and their sizes. */
void scanFolder(HttpClient client, String accessToken, String path,
                List<String> folderPaths, List<long[]> folderSizes) throws Exception {

    String url;
    if (path.isEmpty()) {
        url = GRAPH_BASE + "/me/drive/root/children";
    } else {
        url = GRAPH_BASE + "/me/drive/root:/" + encodePath(path) + ":/children";
    }

    String response = get(client, url, accessToken);

    if (response.contains("\"error\"")) {
        System.out.printf("\r  ⚠️  Could not scan: %s%n", path);
        return;
    }

    List<String> items = jsonArray(response, "value");

    for (String item : items) {
        if (!item.contains("\"folder\"")) continue;

        String name = jsonString(item, "name");
        long size = jsonLong(item, "size");
        int childCount = jsonInt(item, "childCount");
        String fullPath = path.isEmpty() ? name : path + "/" + name;

        folderPaths.add(fullPath);
        folderSizes.add(new long[]{size, childCount});

        System.out.printf("\r  Scanning... %-60s (%d folders found)",
                truncate(fullPath, 60), folderPaths.size());

        // Recurse into subfolders if this folder has children
        if (childCount > 0) {
            scanFolder(client, accessToken, fullPath, folderPaths, folderSizes);
        }
    }
}

String truncate(String s, int maxLen) {
    return s.length() <= maxLen ? s : "..." + s.substring(s.length() - maxLen + 3);
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

// --- Authentication with token caching ---

String authenticate(HttpClient client) throws Exception {
    if (Files.exists(TOKEN_FILE)) {
        String refreshToken = Files.readString(TOKEN_FILE).trim();
        System.out.print("Refreshing cached token...");

        String tokenResponse = post(client, AUTHORITY + "/token",
                Map.of("client_id", CLIENT_ID,
                        "grant_type", "refresh_token",
                        "refresh_token", refreshToken,
                        "scope", "Files.Read offline_access"));

        if (tokenResponse.contains("\"access_token\"")) {
            saveRefreshToken(tokenResponse);
            System.out.println(" ✓ Session restored!\n");
            return jsonString(tokenResponse, "access_token");
        } else {
            System.out.println(" expired, re-authenticating...\n");
            Files.deleteIfExists(TOKEN_FILE);
        }
    }

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

String post(HttpClient client, String url, Map<String, String> formParams) throws Exception {
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

// --- JSON helpers ---

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

List<String> jsonArray(String json, String key) {
    List<String> items = new ArrayList<>();
    int arrayStart = json.indexOf("\"" + key + "\"");
    if (arrayStart == -1) return items;

    int bracketStart = json.indexOf('[', arrayStart);
    if (bracketStart == -1) return items;

    int depth = 1;
    int itemStart = -1;
    boolean inString = false;
    boolean escaped = false;

    for (int i = bracketStart + 1; i < json.length(); i++) {
        char c = json.charAt(i);

        if (escaped) { escaped = false; continue; }
        if (c == '\\' && inString) { escaped = true; continue; }
        if (c == '"') { inString = !inString; continue; }
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
    if (bytes >= 1024L * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
    return bytes + " B";
}
