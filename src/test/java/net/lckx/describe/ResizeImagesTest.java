package net.lckx.describe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class ResizeImagesTest {

    @TempDir
    Path tempDir;

    @Test
    void parseOptions_defaults() {
        ResizeImages.Options options = ResizeImages.parseOptions(new String[]{});
        assertNull(options.directory());
        assertNull(options.longSide());
        assertNull(options.outputMode());
        assertFalse(options.apply());
    }

    @Test
    void parseOptions_readsPresetAndOutputMode() {
        ResizeImages.Options options = ResizeImages.parseOptions(new String[]{
                "/tmp/photos", "--size=a4", "--output=subfolder", "--apply"
        });
        assertEquals(Path.of("/tmp/photos"), options.directory());
        assertEquals(3500, options.longSide());
        assertEquals(ResizeImages.OutputMode.SUBFOLDER, options.outputMode());
        assertTrue(options.apply());
    }

    @Test
    void parseOptions_customLongSideOverridesPreset() {
        ResizeImages.Options options = ResizeImages.parseOptions(new String[]{
                "--long-side=1800", "/tmp/photos"
        });
        assertEquals(1800, options.longSide());
    }

    @Test
    void parseOptions_rejectsUnknownPreset() {
        assertThrows(ResizeImages.UsageException.class,
                () -> ResizeImages.parseOptions(new String[]{"--size=huge", "/tmp/x"}));
    }

    @Test
    void presetLongSide_matchesDocumentedValues() {
        assertEquals(2500, ResizeImages.presetLongSide("a5"));
        assertEquals(3500, ResizeImages.presetLongSide("a4"));
        assertEquals(5000, ResizeImages.presetLongSide("a3"));
    }

    @Test
    void syntheticExif_isReadableBeforeResize() throws IOException {
        byte[] original = buildSquareJpegWithExifTimestamp(1200);
        Path file = tempDir.resolve("original.jpg");
        Files.write(file, original);

        Optional<PrintMediaLocation.JpegTimestamp> stamp = PrintMediaLocation.readJpegTimestamp(file);
        assertTrue(stamp.isPresent(), "test-fixture EXIF must be readable");
        assertEquals(java.time.LocalDateTime.of(2025, 9, 24, 17, 5, 34), stamp.get().local());
    }

    @Test
    void resizeJpeg_shrinksAndPreservesExif() throws IOException {
        byte[] original = buildSquareJpegWithExifTimestamp(1200);

        byte[] resized = ResizeImages.resizeJpeg(original, 400, 400, 0.85f);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(resized));
        assertNotNull(decoded);
        assertEquals(400, decoded.getWidth());
        assertEquals(400, decoded.getHeight());
        assertTrue(resized.length < original.length,
                "resized JPEG should be smaller (was " + original.length + ", now " + resized.length + ")");

        Path resizedFile = tempDir.resolve("resized.jpg");
        Files.write(resizedFile, resized);
        Optional<PrintMediaLocation.JpegTimestamp> stamp = PrintMediaLocation.readJpegTimestamp(resizedFile);
        assertTrue(stamp.isPresent(), "EXIF DateTimeOriginal should survive the resize");
        assertEquals(java.time.LocalDateTime.of(2025, 9, 24, 17, 5, 34), stamp.get().local());
    }

    @Test
    void readJpegDimensions_returnsWidthAndHeight() throws IOException {
        Path file = tempDir.resolve("s.jpg");
        BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "jpg", file.toFile()));

        ResizeImages.Dimensions dims = ResizeImages.readJpegDimensions(file);

        assertNotNull(dims);
        assertEquals(640, dims.width());
        assertEquals(480, dims.height());
    }

    @Test
    void collectJpegs_skipsResizedSubfolder() throws IOException {
        Path root = tempDir.resolve("photos");
        Path resized = root.resolve("resized");
        Files.createDirectories(resized);
        Path a = root.resolve("a.jpg");
        Path b = resized.resolve("a.jpg");
        writeTinyJpeg(a);
        writeTinyJpeg(b);

        var files = ResizeImages.collectJpegs(root, true);

        assertEquals(1, files.size());
        assertEquals(a, files.get(0));
    }

    private static void writeTinyJpeg(Path path) throws IOException {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "jpg", path.toFile()));
    }

    private static byte[] buildSquareJpegWithExifTimestamp(int side) throws IOException {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                image.setRGB(x, y, new Color(x % 256, y % 256, (x + y) % 256).getRGB());
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", bytes));
        byte[] plain = bytes.toByteArray();
        byte[] exif = buildExifApp1WithDateTime("2025:09:24 17:05:34");
        return ResizeImages.spliceApp1AfterSoi(plain, exif);
    }

    private static byte[] buildExifApp1WithDateTime(String dateTime) {
        java.nio.ByteBuffer tiff = java.nio.ByteBuffer.allocate(256).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I');
        tiff.putShort((short) 42);
        tiff.putInt(8);
        int ifd0Pos = tiff.position();
        tiff.putShort((short) 1);
        int exifSubIfdPointerEntry = tiff.position();
        tiff.putShort((short) 0x8769);
        tiff.putShort((short) 4);
        tiff.putInt(1);
        int exifSubIfdOffsetPos = tiff.position();
        tiff.putInt(0);
        tiff.putInt(0);

        int exifSubIfdPos = tiff.position();
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x9003);
        tiff.putShort((short) 2);
        tiff.putInt(20);
        int datetimeOffsetPos = tiff.position();
        tiff.putInt(0);
        tiff.putInt(0);
        int datetimeStart = tiff.position();
        byte[] dtBytes = dateTime.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(19, dtBytes.length);
        tiff.put(dtBytes);
        tiff.put((byte) 0);

        int tiffLen = tiff.position();
        byte[] tiffBytes = new byte[tiffLen];
        tiff.rewind();
        tiff.get(tiffBytes);
        writeIntLE(tiffBytes, exifSubIfdOffsetPos, exifSubIfdPos);
        writeIntLE(tiffBytes, datetimeOffsetPos, datetimeStart);

        int payloadAfterLengthField = 6 + tiffBytes.length;
        int segLength = 2 + payloadAfterLengthField;
        byte[] out = new byte[2 + segLength];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xE1;
        out[2] = (byte) ((segLength >> 8) & 0xFF);
        out[3] = (byte) (segLength & 0xFF);
        out[4] = 'E'; out[5] = 'x'; out[6] = 'i'; out[7] = 'f';
        out[8] = 0; out[9] = 0;
        System.arraycopy(tiffBytes, 0, out, 10, tiffBytes.length);
        return out;
    }

    private static void writeIntLE(byte[] out, int pos, int value) {
        out[pos] = (byte) (value & 0xFF);
        out[pos + 1] = (byte) ((value >> 8) & 0xFF);
        out[pos + 2] = (byte) ((value >> 16) & 0xFF);
        out[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
