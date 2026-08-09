package net.lckx.describe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PrintMediaLocationTest {

    @TempDir
    Path tempDir;

    @Test
    void parseOptions_defaults() {
        PrintMediaLocation.Options options = PrintMediaLocation.parseOptions(new String[]{"photo.jpg"});

        assertEquals(Path.of("photo.jpg"), options.file());
        assertTrue(options.reverseGeocode());
        assertFalse(options.userAgent().isBlank());
    }

    @Test
    void parseOptions_noGeocodeFlag() {
        PrintMediaLocation.Options options = PrintMediaLocation.parseOptions(
                new String[]{"--no-geocode", "photo.jpg"});

        assertFalse(options.reverseGeocode());
    }

    @Test
    void parseOptions_acceptsTimelineFlag() {
        PrintMediaLocation.Options options = PrintMediaLocation.parseOptions(new String[]{
                "--timeline", "/tmp/Tijdlijn.json",
                "--timeline-max-gap-minutes=10",
                "--photo-zone", "Europe/Brussels",
                "photo.jpg"
        });

        assertEquals(Path.of("/tmp/Tijdlijn.json"), options.timelinePath());
        assertEquals(Duration.ofMinutes(10), options.timelineMaxGap());
        assertEquals("Europe/Brussels", options.photoZone().getId());
    }

    @Test
    void timelineIndex_parsesPointsAndFindsNearest() {
        String json = """
                {"semanticSegments":[
                  {"timelinePath":[
                    {"point":"51.1348°, 2.7587°","time":"2025-08-22T10:21:00.000+02:00"},
                    {"point":"51.1365°, 2.7556°","time":"2025-08-22T10:29:00.000+02:00"}
                  ]}
                ]}
                """;

        PrintMediaLocation.TimelineIndex index = PrintMediaLocation.TimelineIndex.fromJson(json);

        assertEquals(2, index.pointCount());

        Instant photoTime = Instant.parse("2025-08-22T08:26:07Z"); // 10:26:07 +02:00
        Optional<PrintMediaLocation.TimelineMatch> match = index.nearest(photoTime, Duration.ofMinutes(30));

        assertTrue(match.isPresent());
        // 10:26:07 is closer to 10:29 (2:53) than to 10:21 (5:07)
        assertEquals(51.1365, match.get().point().latitude(), 1e-4);
        assertEquals(2.7556, match.get().point().longitude(), 1e-4);
    }

    @Test
    void timelineIndex_returnsVisitLocationWhenPhotoIsInsideVisit() {
        String json = """
                {"semanticSegments":[
                  {"startTime":"2025-08-18T18:47:14.000+02:00",
                   "endTime":"2025-08-18T22:14:59.000+02:00",
                   "visit":{"topCandidate":{"placeLocation":{"latLng":"50.7639617°, 4.2310767°"}}}}
                ]}
                """;
        PrintMediaLocation.TimelineIndex index = PrintMediaLocation.TimelineIndex.fromJson(json);

        assertEquals(1, index.visitCount());

        Instant photoTime = Instant.parse("2025-08-18T17:19:49Z"); // 19:19:49 +02:00
        Optional<PrintMediaLocation.TimelineMatch> match = index.nearest(photoTime, Duration.ofMinutes(30));

        assertTrue(match.isPresent());
        assertTrue(match.get().fromVisit());
        assertEquals(Duration.ZERO, match.get().gap());
        assertEquals(50.7639617, match.get().point().latitude(), 1e-6);
    }

    @Test
    void timelineIndex_rejectsPointsBeyondMaxGap() {
        String json = """
                {"timelinePath":[
                  {"point":"50.0°, 4.0°","time":"2020-01-01T12:00:00.000+00:00"}
                ]}
                """;
        PrintMediaLocation.TimelineIndex index = PrintMediaLocation.TimelineIndex.fromJson(json);

        Optional<PrintMediaLocation.TimelineMatch> match = index.nearest(
                Instant.parse("2025-01-01T12:00:00Z"), Duration.ofMinutes(30));

        assertTrue(match.isEmpty());
    }

    @Test
    void jpegTimestamp_appliesOffsetWhenPresent() {
        java.time.LocalDateTime local = java.time.LocalDateTime.of(2025, 9, 27, 7, 51, 31);
        java.time.ZoneOffset thailand = java.time.ZoneOffset.of("+07:00");
        PrintMediaLocation.JpegTimestamp withOffset = new PrintMediaLocation.JpegTimestamp(local, thailand);
        PrintMediaLocation.JpegTimestamp noOffset = new PrintMediaLocation.JpegTimestamp(local, null);

        Instant expected = local.toInstant(thailand);
        assertEquals(expected, withOffset.toInstant(java.time.ZoneId.of("Europe/Brussels")));

        Instant brussels = local.atZone(java.time.ZoneId.of("Europe/Brussels")).toInstant();
        assertEquals(brussels, noOffset.toInstant(java.time.ZoneId.of("Europe/Brussels")));
    }

    @Test
    void parseTimestampFromFilename_readsSamsungPattern() {
        Optional<java.time.LocalDateTime> a = PrintMediaLocation.parseTimestampFromFilename("20250827_113557.mp4");
        Optional<java.time.LocalDateTime> b = PrintMediaLocation.parseTimestampFromFilename("IMG_20250101_000000.jpg");
        Optional<java.time.LocalDateTime> c = PrintMediaLocation.parseTimestampFromFilename("no-timestamp-here.jpg");

        assertTrue(a.isPresent());
        assertEquals(java.time.LocalDateTime.of(2025, 8, 27, 11, 35, 57), a.get());
        assertTrue(b.isPresent());
        assertEquals(java.time.LocalDateTime.of(2025, 1, 1, 0, 0, 0), b.get());
        assertTrue(c.isEmpty());
    }

    @Test
    void formatGap_readsMinutesAndSeconds() {
        assertEquals("45s", PrintMediaLocation.formatGap(Duration.ofSeconds(45)));
        assertEquals("2 min", PrintMediaLocation.formatGap(Duration.ofMinutes(2)));
        assertEquals("2 min 53s", PrintMediaLocation.formatGap(Duration.ofSeconds(173)));
    }

    @Test
    void parseOptions_rejectsMultipleFiles() {
        assertThrows(PrintMediaLocation.UsageException.class,
                () -> PrintMediaLocation.parseOptions(new String[]{"a.jpg", "b.jpg"}));
    }

    @Test
    void parseOptions_rejectsUnknownOption() {
        assertThrows(PrintMediaLocation.UsageException.class,
                () -> PrintMediaLocation.parseOptions(new String[]{"--nope", "a.jpg"}));
    }

    @Test
    void dmsToDecimal_convertsDegreesMinutesSeconds() {
        double decimal = PrintMediaLocation.dmsToDecimal(new double[]{50.0, 46.0, 55.32});

        assertEquals(50.782033, decimal, 1e-5);
    }

    @Test
    void composeLocation_ordersTownVillageStreet() {
        String body = """
                {"display_name":"...","address":{
                  "road":"Pijnbroekstraat",
                  "hamlet":"Mekingen",
                  "town":"Sint-Pieters-Leeuw",
                  "country":"Belgium"
                }}
                """;

        Optional<String> location = PrintMediaLocation.composeLocation(body);

        assertTrue(location.isPresent());
        assertEquals("Sint-Pieters-Leeuw Mekingen Pijnbroekstraat", location.get());
    }

    @Test
    void composeLocation_dropsNonLatinPartsWhenLatinExists() {
        String body = """
                {"display_name":"...","address":{
                  "road":"Khampangdin Road",
                  "suburb":"แขวงนครพิงค์",
                  "city":"Chiang Mai City Municipality"
                }}
                """;

        Optional<String> location = PrintMediaLocation.composeLocation(body);

        assertTrue(location.isPresent());
        assertEquals("Chiang Mai City Municipality Khampangdin Road", location.get());
    }

    @Test
    void composeLocation_keepsAllPartsWhenAllNonLatin() {
        String body = """
                {"display_name":"เทศบาลนครเชียงใหม่ แขวงนครพิงค์","address":{
                  "suburb":"แขวงนครพิงค์",
                  "city":"เทศบาลนครเชียงใหม่"
                }}
                """;

        Optional<String> location = PrintMediaLocation.composeLocation(body);

        assertTrue(location.isPresent());
        assertTrue(location.get().contains("แขวงนครพิงค์"));
    }

    @Test
    void composeLocation_fallsBackToDisplayName() {
        String body = "{\"display_name\":\"Somewhere over the rainbow\"}";

        Optional<String> location = PrintMediaLocation.composeLocation(body);

        assertTrue(location.isPresent());
        assertEquals("Somewhere over the rainbow", location.get());
    }

    @Test
    void composeLocation_returnsEmptyWhenNothingUseful() {
        String body = "{}";

        Optional<String> location = PrintMediaLocation.composeLocation(body);

        assertTrue(location.isEmpty());
    }

    @Test
    void readGpsFromJpeg_returnsEmptyForNonJpeg() throws IOException {
        Path file = tempDir.resolve("not-a-jpeg.txt");
        Files.writeString(file, "hello");

        assertTrue(PrintMediaLocation.readGpsFromJpeg(file).isEmpty());
    }

    @Test
    void readGpsFromJpeg_parsesSyntheticJpegWithGpsExif() throws IOException {
        Path file = tempDir.resolve("gps.jpg");
        Files.write(file, buildJpegWithGps(50.0, 46.0, 55.32, "N", 4.0, 15.0, 42.48, "E"));

        Optional<PrintMediaLocation.GpsFix> fix = PrintMediaLocation.readGpsFromJpeg(file);

        assertTrue(fix.isPresent());
        assertEquals(50.782033, fix.get().latitude(), 1e-5);
        assertEquals(4.261800, fix.get().longitude(), 1e-5);
    }

    @Test
    void parseIso6709_readsPositiveAndNegative() {
        Optional<PrintMediaLocation.GpsFix> fix1 = PrintMediaLocation.parseIso6709("+50.7823+004.2618/");
        Optional<PrintMediaLocation.GpsFix> fix2 = PrintMediaLocation.parseIso6709("-33.8688-151.2093+45.000/");

        assertTrue(fix1.isPresent());
        assertEquals(50.7823, fix1.get().latitude(), 1e-6);
        assertEquals(4.2618, fix1.get().longitude(), 1e-6);

        assertTrue(fix2.isPresent());
        assertEquals(-33.8688, fix2.get().latitude(), 1e-6);
        assertEquals(-151.2093, fix2.get().longitude(), 1e-6);
    }

    @Test
    void parseIso6709_rejectsGarbage() {
        assertTrue(PrintMediaLocation.parseIso6709("").isEmpty());
        assertTrue(PrintMediaLocation.parseIso6709("hello world").isEmpty());
        assertTrue(PrintMediaLocation.parseIso6709("+95.0+200.0/").isEmpty());
    }

    @Test
    void readGpsFromMp4_findsQuickTimeXyzAtom() throws IOException {
        Path file = tempDir.resolve("gps.mp4");
        Files.write(file, buildMp4WithQuickTimeLocation("+50.7823+004.2618/"));

        Optional<PrintMediaLocation.GpsFix> fix = PrintMediaLocation.readGpsFromMp4(file);

        assertTrue(fix.isPresent());
        assertEquals(50.7823, fix.get().latitude(), 1e-6);
        assertEquals(4.2618, fix.get().longitude(), 1e-6);
    }

    @Test
    void readGpsFromMedia_dispatchesByContentSignature() throws IOException {
        Path jpeg = tempDir.resolve("photo.jpg");
        Files.write(jpeg, buildJpegWithGps(30.0, 0, 0, "S", 60.0, 0, 0, "W"));
        Path mp4 = tempDir.resolve("clip.mp4");
        Files.write(mp4, buildMp4WithQuickTimeLocation("+12.3456+078.9012/"));

        Optional<PrintMediaLocation.GpsFix> photoFix = PrintMediaLocation.readGpsFromMedia(jpeg);
        Optional<PrintMediaLocation.GpsFix> videoFix = PrintMediaLocation.readGpsFromMedia(mp4);

        assertTrue(photoFix.isPresent());
        assertEquals(-30.0, photoFix.get().latitude(), 1e-6);
        assertTrue(videoFix.isPresent());
        assertEquals(12.3456, videoFix.get().latitude(), 1e-6);
        assertEquals(78.9012, videoFix.get().longitude(), 1e-6);
    }

    @Test
    void readGpsFromJpeg_appliesSouthWestSigns() throws IOException {
        Path file = tempDir.resolve("gps-sw.jpg");
        Files.write(file, buildJpegWithGps(30.0, 0.0, 0.0, "S", 60.0, 0.0, 0.0, "W"));

        Optional<PrintMediaLocation.GpsFix> fix = PrintMediaLocation.readGpsFromJpeg(file);

        assertTrue(fix.isPresent());
        assertEquals(-30.0, fix.get().latitude(), 1e-6);
        assertEquals(-60.0, fix.get().longitude(), 1e-6);
    }

    private static byte[] buildJpegWithGps(double latDeg, double latMin, double latSec, String latRef,
                                           double lonDeg, double lonMin, double lonSec, String lonRef) throws IOException {
        // Build minimal EXIF TIFF (little-endian) with an IFD0 that points to a GPS IFD.
        ByteBuffer tiff = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        int tiffStart = tiff.position();
        tiff.put((byte) 'I').put((byte) 'I');
        tiff.putShort((short) 42);
        tiff.putInt(8);

        int ifd0Pos = tiff.position();
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x8825);
        tiff.putShort((short) 4);
        tiff.putInt(1);
        int gpsPointerValuePos = tiff.position();
        tiff.putInt(0);
        tiff.putInt(0);

        int gpsIfdPos = tiff.position();
        tiff.putShort((short) 4);

        int latRationalsPos = gpsIfdPos + 2 + 4 * 12 + 4;
        int lonRationalsPos = latRationalsPos + 3 * 8;

        writeGpsEntry(tiff, 0x0001, 2, 2, refBytes(latRef));
        writeGpsEntry(tiff, 0x0002, 5, 3, intToBytes(latRationalsPos - tiffStart));
        writeGpsEntry(tiff, 0x0003, 2, 2, refBytes(lonRef));
        writeGpsEntry(tiff, 0x0004, 5, 3, intToBytes(lonRationalsPos - tiffStart));

        tiff.putInt(0);

        assertEquals(latRationalsPos, tiff.position(), "computed offset for latitude rationals");
        writeRational(tiff, latDeg);
        writeRational(tiff, latMin);
        writeRational(tiff, latSec);
        writeRational(tiff, lonDeg);
        writeRational(tiff, lonMin);
        writeRational(tiff, lonSec);

        int tiffLength = tiff.position() - tiffStart;
        byte[] tiffBytes = new byte[tiffLength];
        tiff.rewind();
        tiff.get(tiffBytes);

        int gpsIfdOffset = gpsIfdPos - tiffStart;
        tiffBytes[gpsPointerValuePos] = (byte) (gpsIfdOffset & 0xFF);
        tiffBytes[gpsPointerValuePos + 1] = (byte) ((gpsIfdOffset >> 8) & 0xFF);
        tiffBytes[gpsPointerValuePos + 2] = (byte) ((gpsIfdOffset >> 16) & 0xFF);
        tiffBytes[gpsPointerValuePos + 3] = (byte) ((gpsIfdOffset >> 24) & 0xFF);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        int app1Length = 2 + 6 + tiffBytes.length;
        out.write(0xFF);
        out.write(0xE1);
        out.write((app1Length >> 8) & 0xFF);
        out.write(app1Length & 0xFF);
        out.write(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        out.write(tiffBytes);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    private static void writeGpsEntry(ByteBuffer tiff, int tag, int type, int count, byte[] valueOrOffset4) {
        tiff.putShort((short) tag);
        tiff.putShort((short) type);
        tiff.putInt(count);
        assertEquals(4, valueOrOffset4.length);
        tiff.put(valueOrOffset4);
    }

    private static byte[] refBytes(String ref) {
        return new byte[]{(byte) ref.charAt(0), 0, 0, 0};
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    private static void writeRational(ByteBuffer tiff, double value) {
        long denominator = 1_000_000L;
        long numerator = Math.round(value * denominator);
        tiff.putInt((int) numerator);
        tiff.putInt((int) denominator);
    }

    private static byte[] buildMp4WithQuickTimeLocation(String iso6709) throws IOException {
        byte[] textBytes = iso6709.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream xyzPayload = new ByteArrayOutputStream();
        xyzPayload.write((textBytes.length >> 8) & 0xFF);
        xyzPayload.write(textBytes.length & 0xFF);
        xyzPayload.write(0x15);
        xyzPayload.write(0xC7);
        xyzPayload.write(textBytes);
        byte[] xyzBox = box(new byte[]{(byte) 0xA9, 'x', 'y', 'z'}, xyzPayload.toByteArray());

        byte[] udtaBox = box("udta".getBytes(java.nio.charset.StandardCharsets.US_ASCII), xyzBox);
        byte[] moovBox = box("moov".getBytes(java.nio.charset.StandardCharsets.US_ASCII), udtaBox);

        ByteArrayOutputStream ftypPayload = new ByteArrayOutputStream();
        ftypPayload.write("isom".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        ftypPayload.write(new byte[]{0, 0, 0, 0x200 - 0x200});
        ftypPayload.write("isomiso2mp41".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] ftypBox = box("ftyp".getBytes(java.nio.charset.StandardCharsets.US_ASCII), ftypPayload.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ftypBox);
        out.write(moovBox);
        return out.toByteArray();
    }

    private static byte[] box(byte[] type, byte[] payload) {
        int size = 8 + payload.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((size >> 24) & 0xFF);
        out.write((size >> 16) & 0xFF);
        out.write((size >> 8) & 0xFF);
        out.write(size & 0xFF);
        out.write(type, 0, 4);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }
}
