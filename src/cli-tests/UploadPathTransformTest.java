/**
 * Unit tests for the Upload path transformation logic.
 * Tests that folder paths are correctly transformed to OneDrive destination paths.
 * Specifically tests the year extraction from folder names like "20230513 moederdag mama"
 * which should map to "2023/20230513 moederdag mama" in OneDrive.
 *
 * Run with:  java --enable-preview src/net.lckx.onedrive.UploadPathTransformTest.java
 * <p>
 * User: louckxb, Date: 30/04/2026.
 */

import java.nio.file.Path;

private int passed = 0;
private int failed = 0;

void main() {
    System.out.println("Running Upload path transformation tests...\n");

    // --- Path transformation tests ---
    testTransformPath_dateWithDescription();
    testTransformPath_simpleYear();
    testTransformPath_nonYearPrefix();
    testTransformPath_shortFolderName();
    testTransformPath_threeDigitPrefix();
    testTransformPath_fiveDigitPrefix();
    testTransformPath_letterPrefix();
    testTransformPath_specialCharacters();
    testTransformPath_multipleYears_oldPhotos();
    testTransformPath_multipleYears_recentPhotos();

    // Summary
    System.out.println("\n" + "=".repeat(50));
    System.out.printf("Results: %d passed, %d failed, %d total%n", passed, failed, passed + failed);
    System.out.println("=".repeat(50));

    if (failed > 0) {
        System.exit(1);
    }
}

// ========================= Path Transformation Tests =========================

void testTransformPath_dateWithDescription() {
    String folderName = "20230513 moederdag mama";
    String result = suggestRemotePath(folderName);
    assertEquals("2023/20230513 moederdag mama", result,
            "Transform: date with description (20230513 moederdag mama)");
}

void testTransformPath_simpleYear() {
    String folderName = "2025";
    String result = suggestRemotePath(folderName);
    assertEquals("2025/2025", result,
            "Transform: simple year folder");
}

void testTransformPath_nonYearPrefix() {
    String folderName = "abcd folder";
    String result = suggestRemotePath(folderName);
    assertEquals("abcd folder", result,
            "Transform: non-year prefix stays as-is");
}

void testTransformPath_shortFolderName() {
    String folderName = "abc";
    String result = suggestRemotePath(folderName);
    assertEquals("abc", result,
            "Transform: short folder name (less than 4 chars)");
}

void testTransformPath_threeDigitPrefix() {
    String folderName = "999 old files";
    String result = suggestRemotePath(folderName);
    assertEquals("999 old files", result,
            "Transform: three-digit prefix (not a year)");
}

void testTransformPath_fiveDigitPrefix() {
    String folderName = "1a34 mixed";
    String result = suggestRemotePath(folderName);
    assertEquals("1a34 mixed", result,
            "Transform: non-numeric prefix (letter in middle)");
}

void testTransformPath_letterPrefix() {
    String folderName = "2o23 typo year";
    String result = suggestRemotePath(folderName);
    assertEquals("2o23 typo year", result,
            "Transform: prefix with letter (not a valid year)");
}

void testTransformPath_specialCharacters() {
    String folderName = "2024 photos (backup)";
    String result = suggestRemotePath(folderName);
    assertEquals("2024/2024 photos (backup)", result,
            "Transform: year prefix with special characters");
}

void testTransformPath_multipleYears_oldPhotos() {
    String folderName = "2020 family reunion";
    String result = suggestRemotePath(folderName);
    assertEquals("2020/2020 family reunion", result,
            "Transform: older year (2020)");
}

void testTransformPath_multipleYears_recentPhotos() {
    String folderName = "2026 latest pictures";
    String result = suggestRemotePath(folderName);
    assertEquals("2026/2026 latest pictures", result,
            "Transform: recent year (2026)");
}

// ========================= Helper Methods Under Test =========================

String suggestRemotePath(String folderName) {
    String prefix = folderName.length() >= 4 ? folderName.substring(0, 4) : "";
    return prefix.matches("\\d{4}") ? prefix + "/" + folderName : folderName;
}

// ========================= Test Assertion Helpers =========================

void assertEquals(String expected, String actual, String testName) {
    if (expected.equals(actual)) {
        System.out.printf("  ✅ %s%n", testName);
        passed++;
    } else {
        System.out.printf("  ❌ %s%n     expected: \"%s\"%n     actual:   \"%s\"%n", testName, expected, actual);
        failed++;
    }
}
