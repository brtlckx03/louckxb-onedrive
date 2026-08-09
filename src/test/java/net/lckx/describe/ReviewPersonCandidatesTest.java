package net.lckx.describe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewPersonCandidatesTest {
    @TempDir
    Path tempDir;

    @Test
    void isCandidateImage_findsImageFilesThatStillContainFrame() {
        assertTrue(ReviewPersonCandidates.isCandidateImage(Path.of("frame-01-01m26s.jpg")));
        assertTrue(ReviewPersonCandidates.isCandidateImage(Path.of("holiday-frame-01.png")));
        assertTrue(ReviewPersonCandidates.isCandidateImage(Path.of("FRAME-01.JPEG")));

        assertFalse(ReviewPersonCandidates.isCandidateImage(Path.of("Miranda-01-01m26s.jpg")));
        assertFalse(ReviewPersonCandidates.isCandidateImage(Path.of("frame-01-01m26s.txt")));
    }

    @Test
    void findCandidateImages_scansPeopleDirectoryRecursively() throws Exception {
        Path videoDir = tempDir.resolve("holiday");
        Files.createDirectories(videoDir);
        Path candidate = Files.createFile(videoDir.resolve("frame-01-01m26s.jpg"));
        Files.createFile(videoDir.resolve("Miranda-01-01m26s.jpg"));
        Files.createFile(videoDir.resolve("frame-not-image.txt"));

        List<Path> candidates = ReviewPersonCandidates.findCandidateImages(tempDir);

        assertEquals(List.of(candidate), candidates);
    }

    @Test
    void buildRenameTarget_keepsFrameLocationWhenUserTypesPersonName() {
        Path source = tempDir.resolve("frame-01-01m26s.jpg");

        Path target = ReviewPersonCandidates.buildRenameTarget(source, "Miranda");

        assertEquals(tempDir.resolve("Miranda-01-01m26s.jpg"), target);
    }

    @Test
    void buildRenameTarget_keepsFrameLocationAndImageWidth() {
        Path source = tempDir.resolve("frame-02-02m53s-256px.jpg");

        Path target = ReviewPersonCandidates.buildRenameTarget(source, "Miranda");

        assertEquals(tempDir.resolve("Miranda-02-02m53s-256px.jpg"), target);
    }

    @Test
    void buildRenameTarget_doesNotDuplicateFrameLocation() {
        Path source = tempDir.resolve("frame-01-01m26s.jpg");

        Path target = ReviewPersonCandidates.buildRenameTarget(source, "Miranda-01-01m26s");

        assertEquals(tempDir.resolve("Miranda-01-01m26s.jpg"), target);
    }

    @Test
    void buildRenameTarget_usesExactFilenameWhenExtensionIsProvided() {
        Path source = tempDir.resolve("frame-01-01m26s.jpg");

        Path target = ReviewPersonCandidates.buildRenameTarget(source, "Miranda.jpg");

        assertEquals(tempDir.resolve("Miranda.jpg"), target);
    }

    @Test
    void buildRenameTarget_rejectsPathInput() {
        Path source = tempDir.resolve("frame-01-01m26s.jpg");

        assertThrows(ReviewPersonCandidates.UsageException.class,
                () -> ReviewPersonCandidates.buildRenameTarget(source, "../Miranda"));
        assertThrows(ReviewPersonCandidates.UsageException.class,
                () -> ReviewPersonCandidates.buildRenameTarget(source, "folder/Miranda"));
    }

    @Test
    void parseOptions_acceptsViewerAndPeopleDir() {
        ReviewPersonCandidates.Options options = ReviewPersonCandidates.parseOptions(new String[]{
                "--people-dir", "~/video-people",
                "--viewer", "both",
                "--terminal-width=100"
        });

        assertEquals(Path.of(System.getProperty("user.home"), "video-people"), options.peopleDir());
        assertEquals(ReviewPersonCandidates.Viewer.BOTH, options.viewer());
        assertEquals(100, options.terminalWidth());
    }

    @Test
    void terminalApplicationName_mapsCommonMacTerminals() {
        assertEquals("Terminal", ReviewPersonCandidates.terminalApplicationName("Apple_Terminal"));
        assertEquals("iTerm", ReviewPersonCandidates.terminalApplicationName("iTerm.app"));
        assertEquals("Visual Studio Code", ReviewPersonCandidates.terminalApplicationName("vscode"));
        assertEquals("Terminal", ReviewPersonCandidates.terminalApplicationName(null));
    }
}
