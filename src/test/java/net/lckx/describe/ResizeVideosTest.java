package net.lckx.describe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResizeVideosTest {

    @TempDir
    Path tempDir;

    @Test
    void parseOptions_defaults() {
        ResizeVideos.Options options = ResizeVideos.parseOptions(new String[]{});
        assertNull(options.directory());
        assertNull(options.longSide());
        assertNull(options.outputMode());
        assertEquals(23, options.crf());
        assertFalse(options.apply());
        assertFalse(options.hardwareAcceleration());
    }

    @Test
    void parseOptions_readsPresetOutputAndFast() {
        ResizeVideos.Options options = ResizeVideos.parseOptions(new String[]{
                "/tmp/videos", "--size=1080p", "--output=overwrite", "--crf=20", "--fast", "--apply"
        });
        assertEquals(Path.of("/tmp/videos"), options.directory());
        assertEquals(1920, options.longSide());
        assertEquals(ResizeVideos.OutputMode.OVERWRITE, options.outputMode());
        assertEquals(20, options.crf());
        assertTrue(options.hardwareAcceleration());
        assertTrue(options.apply());
    }

    @Test
    void presetLongSide_matchesDocumentedValues() {
        assertEquals(854, ResizeVideos.presetLongSide("480p"));
        assertEquals(1280, ResizeVideos.presetLongSide("720p"));
        assertEquals(1920, ResizeVideos.presetLongSide("1080p"));
    }

    @Test
    void parseOptions_rejectsUnknownPreset() {
        assertThrows(ResizeVideos.UsageException.class,
                () -> ResizeVideos.parseOptions(new String[]{"--size=8k", "/tmp/x"}));
    }

    @Test
    void parseOptions_rejectsOutOfRangeCrf() {
        assertThrows(ResizeVideos.UsageException.class,
                () -> ResizeVideos.parseOptions(new String[]{"--crf=50", "/tmp/x"}));
    }

    @Test
    void collectVideos_findsMp4AndMovSkipsResizedSubfolder() throws IOException {
        Path root = tempDir.resolve("videos");
        Path resized = root.resolve("resized");
        Files.createDirectories(resized);
        Path a = root.resolve("clip.mp4");
        Path b = root.resolve("home.mov");
        Path c = root.resolve("photo.jpg");
        Path d = resized.resolve("clip.mp4");
        Files.createFile(a);
        Files.createFile(b);
        Files.createFile(c);
        Files.createFile(d);

        List<Path> found = ResizeVideos.collectVideos(root, true, "resized");

        assertEquals(2, found.size());
        assertTrue(found.contains(a));
        assertTrue(found.contains(b));
        assertFalse(found.contains(c));
        assertFalse(found.contains(d));
    }
}
