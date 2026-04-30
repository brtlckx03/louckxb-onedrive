package net.lckx.onedrive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the Upload path transformation logic.
 * Tests that folder paths are correctly transformed to OneDrive destination paths.
 * Specifically tests the year extraction from folder names like "20230513 moederdag mama"
 * which should map to "2023/20230513 moederdag mama" in OneDrive.
 */
class UploadPathTransformTest {

    @Test
    void dateWithDescription_shouldPrependYear() {
        assertEquals("2023/20230513 moederdag mama", suggestRemotePath("20230513 moederdag mama"));
    }

    @Test
    void simpleYearFolder_shouldPrependYear() {
        assertEquals("2025/2025", suggestRemotePath("2025"));
    }

    @Test
    void nonYearPrefix_shouldStayAsIs() {
        assertEquals("abcd folder", suggestRemotePath("abcd folder"));
    }

    @Test
    void shortFolderName_shouldStayAsIs() {
        assertEquals("abc", suggestRemotePath("abc"));
    }

    @Test
    void threeDigitPrefix_shouldStayAsIs() {
        assertEquals("999 old files", suggestRemotePath("999 old files"));
    }

    @Test
    void nonNumericPrefix_shouldStayAsIs() {
        assertEquals("1a34 mixed", suggestRemotePath("1a34 mixed"));
    }

    @Test
    void prefixWithLetter_shouldStayAsIs() {
        assertEquals("2o23 typo year", suggestRemotePath("2o23 typo year"));
    }

    @Test
    void yearPrefixWithSpecialCharacters_shouldPrependYear() {
        assertEquals("2024/2024 photos (backup)", suggestRemotePath("2024 photos (backup)"));
    }

    @Test
    void olderYear_shouldPrependYear() {
        assertEquals("2020/2020 family reunion", suggestRemotePath("2020 family reunion"));
    }

    @Test
    void recentYear_shouldPrependYear() {
        assertEquals("2026/2026 latest pictures", suggestRemotePath("2026 latest pictures"));
    }

    /**
     * Replicates the path transformation logic from Upload.java (lines 68-70).
     */
    private String suggestRemotePath(String folderName) {
        String prefix = folderName.length() >= 4 ? folderName.substring(0, 4) : "";
        return prefix.matches("\\d{4}") ? prefix + "/" + folderName : folderName;
    }
}
