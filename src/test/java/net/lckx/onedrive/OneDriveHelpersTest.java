package net.lckx.onedrive;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the shared helper methods used by SearchAndDownload and FindBiggestFolders.
 */
class OneDriveHelpersTest {

    // ========================= jsonString tests =========================

    @Test
    void jsonString_simple() {
        String json = "{\"name\": \"hello\", \"id\": \"123\"}";
        assertEquals("hello", jsonString(json, "name"));
        assertEquals("123", jsonString(json, "id"));
    }

    @Test
    void jsonString_withSpaces() {
        String json = "{\"name\" : \"Club Brugge - Atalanta\"}";
        assertEquals("Club Brugge - Atalanta", jsonString(json, "name"));
    }

    @Test
    void jsonString_escapedQuotes() {
        String json = "{\"msg\": \"say \\\"hello\\\"\"}";
        assertEquals("say \\\"hello\\\"", jsonString(json, "msg"));
    }

    @Test
    void jsonString_missingKey() {
        String json = "{\"name\": \"test\"}";
        assertEquals("", jsonString(json, "missing"));
    }

    @Test
    void jsonString_specialCharsInKey() {
        String json = "{\"@microsoft.graph.downloadUrl\": \"https://example.com/file\"}";
        assertEquals("https://example.com/file", jsonString(json, "@microsoft.graph.downloadUrl"));
    }

    @Test
    void jsonString_unicodeValue() {
        String json = "{\"name\": \"café résumé\"}";
        assertEquals("café résumé", jsonString(json, "name"));
    }

    // ========================= jsonInt tests =========================

    @Test
    void jsonInt_simple() {
        String json = "{\"childCount\": 42}";
        assertEquals(42, jsonInt(json, "childCount"));
    }

    @Test
    void jsonInt_negative() {
        String json = "{\"offset\": -5}";
        assertEquals(-5, jsonInt(json, "offset"));
    }

    @Test
    void jsonInt_missingKey() {
        String json = "{\"count\": 10}";
        assertEquals(0, jsonInt(json, "missing"));
    }

    @Test
    void jsonInt_zero() {
        String json = "{\"childCount\": 0}";
        assertEquals(0, jsonInt(json, "childCount"));
    }

    // ========================= jsonLong tests =========================

    @Test
    void jsonLong_largeValue() {
        String json = "{\"size\": 4831838208}";
        assertEquals(4831838208L, jsonLong(json, "size"));
    }

    @Test
    void jsonLong_missingKey() {
        String json = "{\"size\": 100}";
        assertEquals(0L, jsonLong(json, "missing"));
    }

    // ========================= jsonArray tests =========================

    @Test
    void jsonArray_multipleItems() {
        String json = "{\"value\": [{\"name\": \"a\"}, {\"name\": \"b\"}, {\"name\": \"c\"}]}";
        List<String> items = jsonArray(json, "value");
        assertEquals(3, items.size());
        assertEquals("a", jsonString(items.get(0), "name"));
        assertEquals("c", jsonString(items.get(2), "name"));
    }

    @Test
    void jsonArray_emptyArray() {
        String json = "{\"value\": []}";
        List<String> items = jsonArray(json, "value");
        assertEquals(0, items.size());
    }

    @Test
    void jsonArray_nestedObjects() {
        String json = "{\"value\": [{\"name\": \"folder1\", \"folder\": {\"childCount\": 5}}, " +
                "{\"name\": \"file1\", \"file\": {\"mimeType\": \"image/jpeg\"}}]}";
        List<String> items = jsonArray(json, "value");
        assertEquals(2, items.size());
        assertEquals("folder1", jsonString(items.get(0), "name"));
        assertEquals(5, jsonInt(items.get(0), "childCount"));
        assertEquals("file1", jsonString(items.get(1), "name"));
    }

    @Test
    void jsonArray_nestedArrays() {
        String json = "{\"value\": [{\"tags\": [\"a\", \"b\"], \"name\": \"item1\"}]}";
        List<String> items = jsonArray(json, "value");
        assertEquals(1, items.size());
        assertEquals("item1", jsonString(items.getFirst(), "name"));
    }

    @Test
    void jsonArray_stringsWithBraces() {
        String json = "{\"value\": [{\"desc\": \"use {braces} here\", \"name\": \"test\"}]}";
        List<String> items = jsonArray(json, "value");
        assertEquals(1, items.size());
        assertEquals("test", jsonString(items.getFirst(), "name"));
    }

    @Test
    void jsonArray_missingKey() {
        String json = "{\"other\": [1, 2, 3]}";
        List<String> items = jsonArray(json, "value");
        assertEquals(0, items.size());
    }

    @Test
    void jsonArray_singleItem() {
        String json = "{\"value\": [{\"id\": \"abc\", \"name\": \"only\"}]}";
        List<String> items = jsonArray(json, "value");
        assertEquals(1, items.size());
        assertEquals("only", jsonString(items.getFirst(), "name"));
    }

    @Test
    void jsonArray_graphApiResponse() {
        String json = """
                {"@odata.context":"https://graph.microsoft.com/v1.0/$metadata#users('id')/drive/root/children","value":[{"createdDateTime":"2024-01-28T10:00:00Z","id":"ABC123","name":"20240128 RSCA-Union","size":1536000,"folder":{"childCount":12},"parentReference":{"path":"/drive/root:"}},{"createdDateTime":"2024-02-07T10:00:00Z","id":"DEF456","name":"20240207 Bubble World","size":2048000,"folder":{"childCount":8},"parentReference":{"path":"/drive/root:"}}]}""";
        List<String> items = jsonArray(json, "value");
        assertEquals(2, items.size());
        assertEquals("20240128 RSCA-Union", jsonString(items.getFirst(), "name"));
        assertEquals(1536000L, jsonLong(items.get(0), "size"));
        assertEquals(12, jsonInt(items.get(0), "childCount"));
        assertEquals("20240207 Bubble World", jsonString(items.get(1), "name"));
    }

    // ========================= formatSize tests =========================

    @Test
    void formatSize_bytes() {
        assertEquals("500 B", formatSize(500));
    }

    @Test
    void formatSize_kilobytes() {
        String result = formatSize(145408);
        assertTrue(result.endsWith("KB") && result.startsWith("142"));
    }

    @Test
    void formatSize_megabytes() {
        String result = formatSize(3355443);
        assertTrue(result.endsWith("MB") && result.startsWith("3"));
    }

    @Test
    void formatSize_gigabytes() {
        String result = formatSize(4831838208L);
        assertTrue(result.endsWith("GB") && result.startsWith("4"));
    }

    @Test
    void formatSize_zero() {
        assertEquals("0 B", formatSize(0));
    }

    @Test
    void formatSize_boundary() {
        assertTrue(formatSize(1024).endsWith("KB") && formatSize(1024).startsWith("1"));
        assertTrue(formatSize(1024 * 1024).endsWith("MB") && formatSize(1024 * 1024).startsWith("1"));
        assertTrue(formatSize(1024L * 1024 * 1024).endsWith("GB") && formatSize(1024L * 1024 * 1024).startsWith("1"));
    }

    // ========================= encodePath tests =========================

    @Test
    void encodePath_simple() {
        assertEquals("2025", encodePath("2025"));
    }

    @Test
    void encodePath_withSpaces() {
        assertEquals("Joost%20wereld%20fotos", encodePath("Joost wereld fotos"));
    }

    @Test
    void encodePath_nested() {
        assertEquals("2025/20250212%20Club%20Brugge", encodePath("2025/20250212 Club Brugge"));
    }

    @Test
    void encodePath_specialChars() {
        assertEquals("folder%20%28copy%29", encodePath("folder (copy)"));
    }

    // ========================= truncate tests =========================

    @Test
    void truncate_shortString() {
        assertEquals("short", truncate("short", 20));
    }

    @Test
    void truncate_exactLength() {
        assertEquals("12345", truncate("12345", 5));
    }

    @Test
    void truncate_longString() {
        String result = truncate("this is a very long string indeed", 15);
        assertTrue(result.startsWith("..."));
        assertEquals(15, result.length());
    }

    // ========================= Helper methods under test =========================

    private String jsonString(String json, String key) {
        var pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        var matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private int jsonInt(String json, String key) {
        var pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        var matcher = pattern.matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private long jsonLong(String json, String key) {
        var pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        var matcher = pattern.matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    private List<String> jsonArray(String json, String key) {
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

    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private String encodePath(String path) {
        String[] segments = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : "..." + s.substring(s.length() - maxLen + 3);
    }
}
