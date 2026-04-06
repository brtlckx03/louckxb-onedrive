/**
 * Unit tests for the shared helper methods used by SearchAndDownload and FindBiggestFolders.
 * Run with:  java --enable-preview src/net.lckx.onedrive.OneDriveHelpersTest.java
 * <p>
 * User: louckxb, Date: 29/03/2026.
 */

private int passed = 0;
private int failed = 0;

void main() {
    System.out.println("Running OneDrive helper tests...\n");

    // --- jsonString tests ---
    testJsonString_simple();
    testJsonString_withSpaces();
    testJsonString_escapedQuotes();
    testJsonString_missingKey();
    testJsonString_specialCharsInKey();
    testJsonString_unicodeValue();

    // --- jsonInt tests ---
    testJsonInt_simple();
    testJsonInt_negative();
    testJsonInt_missingKey();
    testJsonInt_zero();

    // --- jsonLong tests ---
    testJsonLong_largeValue();
    testJsonLong_missingKey();

    // --- jsonArray tests ---
    testJsonArray_multipleItems();
    testJsonArray_emptyArray();
    testJsonArray_nestedObjects();
    testJsonArray_nestedArrays();
    testJsonArray_stringsWithBraces();
    testJsonArray_missingKey();
    testJsonArray_singleItem();
    testJsonArray_graphApiResponse();

    // --- formatSize tests ---
    testFormatSize_bytes();
    testFormatSize_kilobytes();
    testFormatSize_megabytes();
    testFormatSize_gigabytes();
    testFormatSize_zero();
    testFormatSize_boundary();

    // --- encodePath tests ---
    testEncodePath_simple();
    testEncodePath_withSpaces();
    testEncodePath_nested();
    testEncodePath_specialChars();

    // --- truncate tests ---
    testTruncate_shortString();
    testTruncate_exactLength();
    testTruncate_longString();

    // Summary
    System.out.println("\n" + "=".repeat(50));
    System.out.printf("Results: %d passed, %d failed, %d total%n", passed, failed, passed + failed);
    System.out.println("=".repeat(50));

    if (failed > 0) {
        System.exit(1);
    }
}

// ========================= jsonString tests =========================

void testJsonString_simple() {
    String json = "{\"name\": \"hello\", \"id\": \"123\"}";
    assertEquals("hello", jsonString(json, "name"), "jsonString: simple value");
    assertEquals("123", jsonString(json, "id"), "jsonString: second key");
}

void testJsonString_withSpaces() {
    String json = "{\"name\" : \"Club Brugge - Atalanta\"}";
    assertEquals("Club Brugge - Atalanta", jsonString(json, "name"), "jsonString: value with spaces and dashes");
}

void testJsonString_escapedQuotes() {
    String json = "{\"msg\": \"say \\\"hello\\\"\"}";
    assertEquals("say \\\"hello\\\"", jsonString(json, "msg"), "jsonString: escaped quotes");
}

void testJsonString_missingKey() {
    String json = "{\"name\": \"test\"}";
    assertEquals("", jsonString(json, "missing"), "jsonString: missing key returns empty");
}

void testJsonString_specialCharsInKey() {
    String json = "{\"@microsoft.graph.downloadUrl\": \"https://example.com/file\"}";
    assertEquals("https://example.com/file", jsonString(json, "@microsoft.graph.downloadUrl"),
            "jsonString: key with @ and dots");
}

void testJsonString_unicodeValue() {
    String json = "{\"name\": \"café résumé\"}";
    assertEquals("café résumé", jsonString(json, "name"), "jsonString: unicode characters");
}

// ========================= jsonInt tests =========================

void testJsonInt_simple() {
    String json = "{\"childCount\": 42}";
    assertIntEquals(42, jsonInt(json, "childCount"), "jsonInt: simple integer");
}

void testJsonInt_negative() {
    String json = "{\"offset\": -5}";
    assertIntEquals(-5, jsonInt(json, "offset"), "jsonInt: negative integer");
}

void testJsonInt_missingKey() {
    String json = "{\"count\": 10}";
    assertIntEquals(0, jsonInt(json, "missing"), "jsonInt: missing key returns 0");
}

void testJsonInt_zero() {
    String json = "{\"childCount\": 0}";
    assertIntEquals(0, jsonInt(json, "childCount"), "jsonInt: zero value");
}

// ========================= jsonLong tests =========================

void testJsonLong_largeValue() {
    String json = "{\"size\": 4831838208}";
    assertLongEquals(4831838208L, jsonLong(json, "size"), "jsonLong: large value (4.5 GB)");
}

void testJsonLong_missingKey() {
    String json = "{\"size\": 100}";
    assertLongEquals(0L, jsonLong(json, "missing"), "jsonLong: missing key returns 0");
}

// ========================= jsonArray tests =========================

void testJsonArray_multipleItems() {
    String json = "{\"value\": [{\"name\": \"a\"}, {\"name\": \"b\"}, {\"name\": \"c\"}]}";
    List<String> items = jsonArray(json, "value");
    assertIntEquals(3, items.size(), "jsonArray: 3 items");
    assertEquals("a", jsonString(items.get(0), "name"), "jsonArray: first item name");
    assertEquals("c", jsonString(items.get(2), "name"), "jsonArray: last item name");
}

void testJsonArray_emptyArray() {
    String json = "{\"value\": []}";
    List<String> items = jsonArray(json, "value");
    assertIntEquals(0, items.size(), "jsonArray: empty array");
}

void testJsonArray_nestedObjects() {
    String json = "{\"value\": [{\"name\": \"folder1\", \"folder\": {\"childCount\": 5}}, " +
            "{\"name\": \"file1\", \"file\": {\"mimeType\": \"image/jpeg\"}}]}";
    List<String> items = jsonArray(json, "value");
    assertIntEquals(2, items.size(), "jsonArray: items with nested objects");
    assertEquals("folder1", jsonString(items.get(0), "name"), "jsonArray: nested obj - first name");
    assertIntEquals(5, jsonInt(items.get(0), "childCount"), "jsonArray: nested childCount");
    assertEquals("file1", jsonString(items.get(1), "name"), "jsonArray: nested obj - second name");
}

void testJsonArray_nestedArrays() {
    String json = "{\"value\": [{\"tags\": [\"a\", \"b\"], \"name\": \"item1\"}]}";
    List<String> items = jsonArray(json, "value");
    assertIntEquals(1, items.size(), "jsonArray: item with nested array");
    assertEquals("item1", jsonString(items.getFirst(), "name"), "jsonArray: name from item with nested array");
}

void testJsonArray_stringsWithBraces() {
    String json = "{\"value\": [{\"desc\": \"use {braces} here\", \"name\": \"test\"}]}";
    List<String> items = jsonArray(json, "value");
    assertIntEquals(1, items.size(), "jsonArray: braces inside strings");
    assertEquals("test", jsonString(items.getFirst(), "name"), "jsonArray: name despite braces in string");
}

void testJsonArray_missingKey() {
    String json = "{\"other\": [1, 2, 3]}";
    List<String> items = jsonArray(json, "value");
    assertIntEquals(0, items.size(), "jsonArray: missing key returns empty list");
}

void testJsonArray_singleItem() {
    String json = "{\"value\": [{\"id\": \"abc\", \"name\": \"only\"}]}";
    List<String> items = jsonArray(json, "value");
    assertIntEquals(1, items.size(), "jsonArray: single item");
    assertEquals("only", jsonString(items.getFirst(), "name"), "jsonArray: single item name");
}

void testJsonArray_graphApiResponse() {
    // Simulates a real Microsoft Graph API response structure
    String json = """
            {"@odata.context":"https://graph.microsoft.com/v1.0/$metadata#users('id')/drive/root/children","value":[{"createdDateTime":"2024-01-28T10:00:00Z","id":"ABC123","name":"20240128 RSCA-Union","size":1536000,"folder":{"childCount":12},"parentReference":{"path":"/drive/root:"}},{"createdDateTime":"2024-02-07T10:00:00Z","id":"DEF456","name":"20240207 Bubble World","size":2048000,"folder":{"childCount":8},"parentReference":{"path":"/drive/root:"}}]}""";
    List<String> items = jsonArray(json, "value");
    assertIntEquals(2, items.size(), "jsonArray: Graph API response - item count");
    assertEquals("20240128 RSCA-Union", jsonString(items.getFirst(), "name"),
            "jsonArray: Graph API response - first folder name");
    assertLongEquals(1536000L, jsonLong(items.get(0), "size"),
            "jsonArray: Graph API response - first folder size");
    assertIntEquals(12, jsonInt(items.get(0), "childCount"),
            "jsonArray: Graph API response - first folder childCount");
    assertEquals("20240207 Bubble World", jsonString(items.get(1), "name"),
            "jsonArray: Graph API response - second folder name");
}

// ========================= formatSize tests =========================

void testFormatSize_bytes() {
    assertEquals("500 B", formatSize(500), "formatSize: bytes");
}

void testFormatSize_kilobytes() {
    String result = formatSize(145408);
    assertTrue(result.endsWith("KB") && result.startsWith("142"), "formatSize: kilobytes");
}

void testFormatSize_megabytes() {
    String result = formatSize(3355443);
    assertTrue(result.endsWith("MB") && result.startsWith("3"), "formatSize: megabytes");
}

void testFormatSize_gigabytes() {
    String result = formatSize(4831838208L);
    assertTrue(result.endsWith("GB") && result.startsWith("4"), "formatSize: gigabytes");
}

void testFormatSize_zero() {
    assertEquals("0 B", formatSize(0), "formatSize: zero");
}

void testFormatSize_boundary() {
    String kb = formatSize(1024);
    assertTrue(kb.endsWith("KB") && kb.startsWith("1"), "formatSize: exactly 1 KB");
    String mb = formatSize(1024 * 1024);
    assertTrue(mb.endsWith("MB") && mb.startsWith("1"), "formatSize: exactly 1 MB");
    String gb = formatSize(1024L * 1024 * 1024);
    assertTrue(gb.endsWith("GB") && gb.startsWith("1"), "formatSize: exactly 1 GB");
}

// ========================= encodePath tests =========================

void testEncodePath_simple() {
    assertEquals("2025", encodePath("2025"), "encodePath: simple path");
}

void testEncodePath_withSpaces() {
    assertEquals("Joost%20wereld%20fotos", encodePath("Joost wereld fotos"),
            "encodePath: spaces encoded as %20");
}

void testEncodePath_nested() {
    assertEquals("2025/20250212%20Club%20Brugge", encodePath("2025/20250212 Club Brugge"),
            "encodePath: nested path preserves /");
}

void testEncodePath_specialChars() {
    assertEquals("folder%20%28copy%29", encodePath("folder (copy)"),
            "encodePath: parentheses encoded");
}

// ========================= truncate tests =========================

void testTruncate_shortString() {
    assertEquals("short", truncate("short", 20), "truncate: short string unchanged");
}

void testTruncate_exactLength() {
    assertEquals("12345", truncate("12345", 5), "truncate: exact length unchanged");
}

void testTruncate_longString() {
    String result = truncate("this is a very long string indeed", 15);
    assertTrue(result.startsWith("..."), "truncate: starts with ...");
    assertIntEquals(15, result.length(), "truncate: respects maxLen");
}

// ========================= Helper methods under test =========================
// (Copied from the applications — these are the pure functions being tested)

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

@SuppressWarnings("SameParameterValue")
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
    if (bytes >= 1024L * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
    return bytes + " B";
}

String encodePath(String path) {
    String[] segments = path.split("/");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segments.length; i++) {
        if (i > 0) sb.append('/');
        sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return sb.toString();
}

String truncate(String s, int maxLen) {
    return s.length() <= maxLen ? s : "..." + s.substring(s.length() - maxLen + 3);
}

// ========================= Test assertion helpers =========================

void assertEquals(String expected, String actual, String testName) {
    if (expected.equals(actual)) {
        System.out.printf("  ✅ %s%n", testName);
        passed++;
    } else {
        System.out.printf("  ❌ %s%n     expected: \"%s\"%n     actual:   \"%s\"%n", testName, expected, actual);
        failed++;
    }
}

void assertIntEquals(int expected, int actual, String testName) {
    if (expected == actual) {
        System.out.printf("  ✅ %s%n", testName);
        passed++;
    } else {
        System.out.printf("  ❌ %s%n     expected: %d%n     actual:   %d%n", testName, expected, actual);
        failed++;
    }
}

void assertLongEquals(long expected, long actual, String testName) {
    if (expected == actual) {
        System.out.printf("  ✅ %s%n", testName);
        passed++;
    } else {
        System.out.printf("  ❌ %s%n     expected: %d%n     actual:   %d%n", testName, expected, actual);
        failed++;
    }
}

void assertTrue(boolean condition, String testName) {
    if (condition) {
        System.out.printf("  ✅ %s%n", testName);
        passed++;
    } else {
        System.out.printf("  ❌ %s%n     condition was false%n", testName);
        failed++;
    }
}
