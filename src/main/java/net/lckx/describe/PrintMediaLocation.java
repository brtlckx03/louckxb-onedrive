package net.lckx.describe;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prints where a photo or video was taken.
 *
 * Reads GPS coordinates from JPEG EXIF or MP4/MOV metadata (pure Java, no external tools),
 * then reverse-geocodes them with the free Nominatim (OpenStreetMap) service to produce a
 * human-readable location such as "Sint-Pieters-Leeuw Mekingen Pijnbroekstraat".
 */
public class PrintMediaLocation {

    private static final String NOMINATIM_HOST = "https://nominatim.openstreetmap.org";
    private static final String DEFAULT_USER_AGENT = "net.lckx.printmedialocation/1.0 (personal CLI)";
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;
    private static final int GPS_INFO_TAG = 0x8825;
    private static final int EXIF_SUB_IFD_TAG = 0x8769;
    private static final int DATETIME_ORIGINAL_TAG = 0x9003;
    private static final int OFFSET_TIME_ORIGINAL_TAG = 0x9011;
    private static final Duration DEFAULT_TIMELINE_MAX_GAP = Duration.ofMinutes(30);
    private static final DateTimeFormatter EXIF_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    static String defaultAcceptLanguage() {
        String lang = Locale.getDefault().getLanguage();
        if (lang == null || lang.isBlank()) return "en";
        if (lang.equalsIgnoreCase("en")) return "en";
        return lang + ",en;q=0.5";
    }

    public static void main(String[] args) {
        int exitCode = new PrintMediaLocation().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        try {
            Options options = parseOptions(args);
            if (options.file() == null) {
                options = options.withFile(promptForFilePath());
            }
            validateFilePath(options.file());

            Optional<GpsFix> gpsFix = readGpsFromMedia(options.file());
            String source = "EXIF/atom";
            Duration timelineGap = Duration.ZERO;

            if (gpsFix.isEmpty() && options.timelinePath() != null) {
                Optional<Instant> mediaInstant = readMediaInstant(options.file(), options.photoZone());
                if (mediaInstant.isPresent()) {
                    if (options.timelineAutoDetected()) {
                        System.out.println("Using auto-detected Timeline: " + options.timelinePath());
                    }
                    TimelineIndex timeline = TimelineIndex.load(options.timelinePath());
                    Optional<TimelineMatch> match = timeline.nearest(mediaInstant.get(), options.timelineMaxGap());
                    if (match.isPresent()) {
                        gpsFix = Optional.of(new GpsFix(match.get().point().latitude(), match.get().point().longitude()));
                        source = "Google Timeline";
                        timelineGap = match.get().gap().abs();
                    }
                }
            }

            System.out.println("File: " + options.file().toAbsolutePath());
            if (gpsFix.isEmpty()) {
                System.out.println("No GPS location found in this file's metadata.");
                System.out.println("(Supported: JPEG EXIF, MP4/MOV location atoms, and Google Timeline via --timeline.)");
                return 0;
            }

            GpsFix fix = gpsFix.get();
            if (source.equals("Google Timeline")) {
                System.out.println(String.format(Locale.ROOT,
                        "GPS:  %.6f, %.6f  (from Google Timeline, %s from photo time)",
                        fix.latitude(), fix.longitude(), formatGap(timelineGap)));
            } else {
                System.out.println(String.format(Locale.ROOT, "GPS:  %.6f, %.6f", fix.latitude(), fix.longitude()));
            }

            if (options.reverseGeocode()) {
                Optional<String> location = reverseGeocode(fix, options.userAgent(), options.timeout(), options.acceptLanguage());
                location.ifPresentOrElse(
                        loc -> System.out.println("Location: " + loc),
                        () -> System.out.println("Location: (no address returned by Nominatim)"));
            }
            return 0;
        } catch (HelpException e) {
            printUsage();
            return 0;
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            System.err.println();
            printUsage();
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted.");
            return 130;
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static Options parseOptions(String[] args) {
        Path file = null;
        boolean reverseGeocode = true;
        String userAgent = DEFAULT_USER_AGENT;
        Duration timeout = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
        Path timelinePath = null;
        Duration timelineMaxGap = DEFAULT_TIMELINE_MAX_GAP;
        ZoneId photoZone = ZoneId.systemDefault();
        String acceptLanguage = defaultAcceptLanguage();
        boolean suggestFilename = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                throw new HelpException();
            } else if (arg.equals("--no-geocode")) {
                reverseGeocode = false;
            } else if (arg.equals("--user-agent")) {
                userAgent = requireValue(args, ++i, "--user-agent");
            } else if (arg.startsWith("--user-agent=")) {
                userAgent = valueAfterEquals(arg, "--user-agent");
            } else if (arg.equals("--timeout-seconds")) {
                timeout = Duration.ofSeconds(parseBoundedInt(requireValue(args, ++i, "--timeout-seconds"),
                        "--timeout-seconds", 1, 300));
            } else if (arg.startsWith("--timeout-seconds=")) {
                timeout = Duration.ofSeconds(parseBoundedInt(valueAfterEquals(arg, "--timeout-seconds"),
                        "--timeout-seconds", 1, 300));
            } else if (arg.equals("--timeline")) {
                timelinePath = expandHomePath(requireValue(args, ++i, "--timeline"));
            } else if (arg.startsWith("--timeline=")) {
                timelinePath = expandHomePath(valueAfterEquals(arg, "--timeline"));
            } else if (arg.equals("--timeline-max-gap-minutes")) {
                timelineMaxGap = Duration.ofMinutes(parseBoundedInt(requireValue(args, ++i, "--timeline-max-gap-minutes"),
                        "--timeline-max-gap-minutes", 1, 1440));
            } else if (arg.startsWith("--timeline-max-gap-minutes=")) {
                timelineMaxGap = Duration.ofMinutes(parseBoundedInt(valueAfterEquals(arg, "--timeline-max-gap-minutes"),
                        "--timeline-max-gap-minutes", 1, 1440));
            } else if (arg.equals("--photo-zone")) {
                photoZone = ZoneId.of(requireValue(args, ++i, "--photo-zone"));
            } else if (arg.startsWith("--photo-zone=")) {
                photoZone = ZoneId.of(valueAfterEquals(arg, "--photo-zone"));
            } else if (arg.equals("--language")) {
                acceptLanguage = requireValue(args, ++i, "--language");
            } else if (arg.startsWith("--language=")) {
                acceptLanguage = valueAfterEquals(arg, "--language");
            } else if (arg.equals("--suggest-filename")) {
                suggestFilename = true;
            } else if (arg.startsWith("-")) {
                throw new UsageException("Unknown option: " + arg);
            } else if (file == null) {
                file = expandHomePath(arg);
            } else {
                throw new UsageException("Only one file can be processed at a time.");
            }
        }
        boolean timelineAutoDetected = false;
        if (timelinePath == null) {
            timelinePath = autoDetectTimeline();
            timelineAutoDetected = timelinePath != null;
        }
        return new Options(file, reverseGeocode, userAgent, timeout, timelinePath, timelineMaxGap, photoZone, timelineAutoDetected, acceptLanguage, suggestFilename);
    }

    private static final List<Path> TIMELINE_AUTO_DETECT_PATHS = List.of(
            Path.of(System.getProperty("user.dir"), "src/main/resources/Tijdlijn.json"),
            Path.of(System.getProperty("user.dir"), "src/main/resources/Timeline.json"),
            Path.of(System.getProperty("user.dir"), "Tijdlijn.json"),
            Path.of(System.getProperty("user.dir"), "Timeline.json"),
            Path.of(System.getProperty("user.home"), "Downloads/Tijdlijn.json"),
            Path.of(System.getProperty("user.home"), "Downloads/Timeline.json"),
            Path.of(System.getProperty("user.home"), "Downloads/Takeout/Tijdlijn/Tijdlijn.json"),
            Path.of(System.getProperty("user.home"), "Downloads/Takeout/Timeline/Timeline.json")
    );

    static Path autoDetectTimeline() {
        for (Path candidate : TIMELINE_AUTO_DETECT_PATHS) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path promptForFilePath() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("Media file: ");
            System.out.flush();
            if (!scanner.hasNextLine()) {
                throw new UsageException("Missing media file.");
            }
            String input = scanner.nextLine().trim();
            if ((input.startsWith("\"") && input.endsWith("\""))
                    || (input.startsWith("'") && input.endsWith("'"))) {
                if (input.length() >= 2) {
                    input = input.substring(1, input.length() - 1).trim();
                }
            }
            if (!input.isEmpty()) {
                return expandHomePath(input);
            }
            System.out.println("Please enter a path to an image or video file (or press Ctrl+C to cancel).");
        }
    }

    private static void validateFilePath(Path file) {
        if (!Files.exists(file)) {
            throw new UsageException("File not found: " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new UsageException("Not a regular file: " + file);
        }
        if (!Files.isReadable(file)) {
            throw new UsageException("File is not readable: " + file);
        }
    }

    static Optional<GpsFix> readGpsFromMedia(Path file) throws IOException {
        byte[] header = readHeaderBytes(file, 12);
        if (header.length >= 2 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8) {
            return readGpsFromJpeg(file);
        }
        if (isMp4Header(header)) {
            return readGpsFromMp4(file);
        }
        return Optional.empty();
    }

    private static byte[] readHeaderBytes(Path file, int count) throws IOException {
        byte[] header = new byte[count];
        try (var stream = Files.newInputStream(file)) {
            int read = stream.readNBytes(header, 0, count);
            if (read < count) {
                byte[] shorter = new byte[read];
                System.arraycopy(header, 0, shorter, 0, read);
                return shorter;
            }
        }
        return header;
    }

    private static boolean isMp4Header(byte[] header) {
        return header.length >= 8
                && header[4] == 'f' && header[5] == 't'
                && header[6] == 'y' && header[7] == 'p';
    }

    static Optional<GpsFix> readGpsFromJpeg(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        int tiffStart = findExifTiffStart(data);
        if (tiffStart < 0) {
            return Optional.empty();
        }
        return parseExifTiff(data, tiffStart);
    }

    private static int findExifTiffStart(byte[] data) {
        if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return -1;
        }
        int pos = 2;
        while (pos + 3 < data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                return -1;
            }
            int marker = data[pos + 1] & 0xFF;
            pos += 2;
            if (marker == 0xD9 || marker == 0xDA) {
                return -1;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            if (pos + 2 > data.length) return -1;
            int segLength = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            int segStart = pos + 2;
            int segEnd = pos + segLength;
            if (segLength < 2 || segEnd > data.length) return -1;
            pos += segLength;
            if (marker == 0xE1 && segEnd - segStart >= 6
                    && data[segStart] == 'E' && data[segStart + 1] == 'x'
                    && data[segStart + 2] == 'i' && data[segStart + 3] == 'f'
                    && data[segStart + 4] == 0 && data[segStart + 5] == 0) {
                return segStart + 6;
            }
        }
        return -1;
    }

    private static Optional<GpsFix> parseExifTiff(byte[] data, int tiffStart) {
        if (data.length - tiffStart < 8) return Optional.empty();
        boolean littleEndian;
        if (data[tiffStart] == 'I' && data[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (data[tiffStart] == 'M' && data[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return Optional.empty();
        }
        int magic = readShort(data, tiffStart + 2, littleEndian);
        if (magic != 42) return Optional.empty();
        long firstIfdOffset = readUnsignedLong(data, tiffStart + 4, littleEndian);
        int ifd0Pos = tiffStart + (int) firstIfdOffset;
        int gpsIfdOffset = findIfdEntryValue(data, ifd0Pos, GPS_INFO_TAG, littleEndian);
        if (gpsIfdOffset < 0) return Optional.empty();
        return parseGpsIfd(data, tiffStart, tiffStart + gpsIfdOffset, littleEndian);
    }

    private static int findIfdEntryValue(byte[] data, int ifdPos, int soughtTag, boolean littleEndian) {
        if (ifdPos + 2 > data.length) return -1;
        int entryCount = readShort(data, ifdPos, littleEndian);
        for (int i = 0; i < entryCount; i++) {
            int entryPos = ifdPos + 2 + i * 12;
            if (entryPos + 12 > data.length) return -1;
            int tag = readShort(data, entryPos, littleEndian);
            if (tag == soughtTag) {
                return (int) readUnsignedLong(data, entryPos + 8, littleEndian);
            }
        }
        return -1;
    }

    private static Optional<GpsFix> parseGpsIfd(byte[] data, int tiffStart, int gpsIfdPos, boolean littleEndian) {
        if (gpsIfdPos + 2 > data.length) return Optional.empty();
        int entryCount = readShort(data, gpsIfdPos, littleEndian);
        String latRef = null;
        String lonRef = null;
        double[] latDMS = null;
        double[] lonDMS = null;

        for (int i = 0; i < entryCount; i++) {
            int entryPos = gpsIfdPos + 2 + i * 12;
            if (entryPos + 12 > data.length) return Optional.empty();
            int tag = readShort(data, entryPos, littleEndian);
            long count = readUnsignedLong(data, entryPos + 4, littleEndian);
            switch (tag) {
                case 0x0001 -> latRef = readAsciiInline(data, entryPos + 8, (int) Math.min(count, 4));
                case 0x0002 -> latDMS = readRationals(data, tiffStart, entryPos, count, littleEndian);
                case 0x0003 -> lonRef = readAsciiInline(data, entryPos + 8, (int) Math.min(count, 4));
                case 0x0004 -> lonDMS = readRationals(data, tiffStart, entryPos, count, littleEndian);
                default -> {
                }
            }
        }

        if (latRef == null || lonRef == null || latDMS == null || lonDMS == null) {
            return Optional.empty();
        }

        double lat = dmsToDecimal(latDMS);
        double lon = dmsToDecimal(lonDMS);
        if (latRef.startsWith("S")) lat = -lat;
        if (lonRef.startsWith("W")) lon = -lon;
        return Optional.of(new GpsFix(lat, lon));
    }

    private static double[] readRationals(byte[] data, int tiffStart, int entryPos, long count, boolean littleEndian) {
        long offset = readUnsignedLong(data, entryPos + 8, littleEndian);
        int pos = tiffStart + (int) offset;
        if (pos < 0 || pos + count * 8 > data.length) return null;
        double[] result = new double[(int) count];
        for (int i = 0; i < count; i++) {
            long num = readUnsignedLong(data, pos + i * 8, littleEndian);
            long den = readUnsignedLong(data, pos + i * 8 + 4, littleEndian);
            result[i] = den == 0 ? 0.0 : (double) num / (double) den;
        }
        return result;
    }

    static double dmsToDecimal(double[] dms) {
        if (dms == null || dms.length < 1) return 0;
        double degrees = dms[0];
        double minutes = dms.length > 1 ? dms[1] : 0;
        double seconds = dms.length > 2 ? dms[2] : 0;
        return degrees + minutes / 60.0 + seconds / 3600.0;
    }

    private static int readShort(byte[] data, int pos, boolean littleEndian) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        return littleEndian ? (b1 << 8) | b0 : (b0 << 8) | b1;
    }

    private static long readUnsignedLong(byte[] data, int pos, boolean littleEndian) {
        long b0 = data[pos] & 0xFF;
        long b1 = data[pos + 1] & 0xFF;
        long b2 = data[pos + 2] & 0xFF;
        long b3 = data[pos + 3] & 0xFF;
        return littleEndian
                ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
                : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static String readAsciiInline(byte[] data, int pos, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            byte b = data[pos + i];
            if (b == 0) break;
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
    }

    static Optional<GpsFix> readGpsFromMp4(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            return findGpsInBoxes(raf, 0L, raf.length(), 0);
        }
    }

    private static Optional<GpsFix> findGpsInBoxes(RandomAccessFile raf, long start, long end, int depth) throws IOException {
        if (depth > 16) return Optional.empty();
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            long size = Integer.toUnsignedLong(raf.readInt());
            byte[] typeBytes = new byte[4];
            raf.readFully(typeBytes);
            long headerSize = 8;
            if (size == 1) {
                if (pos + 16 > end) return Optional.empty();
                size = raf.readLong();
                headerSize = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < headerSize) return Optional.empty();
            long dataStart = pos + headerSize;
            long boxEnd = pos + size;
            if (boxEnd > end || boxEnd < dataStart) return Optional.empty();

            String type = new String(typeBytes, StandardCharsets.ISO_8859_1);

            if (type.equals("©xyz") || type.equals("loci")) {
                Optional<GpsFix> fix = parseLocationAtom(raf, dataStart, boxEnd, type);
                if (fix.isPresent()) return fix;
            } else if (type.equals("moov") || type.equals("udta") || type.equals("trak")
                    || type.equals("mdia") || type.equals("minf") || type.equals("ilst")) {
                Optional<GpsFix> nested = findGpsInBoxes(raf, dataStart, boxEnd, depth + 1);
                if (nested.isPresent()) return nested;
            } else if (type.equals("meta")) {
                Optional<GpsFix> nested = findGpsInBoxes(raf, dataStart + 4, boxEnd, depth + 1);
                if (nested.isPresent()) return nested;
                Optional<GpsFix> stringSearch = searchForIso6709(raf, dataStart, boxEnd);
                if (stringSearch.isPresent()) return stringSearch;
            }

            pos = boxEnd;
        }
        return Optional.empty();
    }

    private static Optional<GpsFix> parseLocationAtom(RandomAccessFile raf, long start, long end, String type) throws IOException {
        long dataLength = end - start;
        if (dataLength > 4096) return Optional.empty();
        byte[] payload = new byte[(int) dataLength];
        raf.seek(start);
        raf.readFully(payload);

        if (type.equals("©xyz")) {
            if (payload.length < 4) return Optional.empty();
            int textLength = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            int textStart = 4;
            int available = Math.max(0, payload.length - textStart);
            int take = Math.min(textLength, available);
            String text = new String(payload, textStart, take, StandardCharsets.UTF_8);
            return parseIso6709(text);
        }

        String text = new String(payload, StandardCharsets.UTF_8);
        return parseIso6709(text);
    }

    private static Optional<GpsFix> searchForIso6709(RandomAccessFile raf, long start, long end) throws IOException {
        long length = end - start;
        if (length <= 0 || length > 65_536) return Optional.empty();
        byte[] buffer = new byte[(int) length];
        raf.seek(start);
        raf.readFully(buffer);
        return parseIso6709(new String(buffer, StandardCharsets.UTF_8));
    }

    private static final Pattern ISO_6709_PATTERN = Pattern.compile(
            "([+-]\\d{1,3}(?:\\.\\d+)?)([+-]\\d{1,3}(?:\\.\\d+)?)");

    static Optional<GpsFix> parseIso6709(String text) {
        if (text == null || text.isEmpty()) return Optional.empty();
        Matcher matcher = ISO_6709_PATTERN.matcher(text);
        if (!matcher.find()) return Optional.empty();
        try {
            double lat = Double.parseDouble(matcher.group(1));
            double lon = Double.parseDouble(matcher.group(2));
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return Optional.empty();
            return Optional.of(new GpsFix(lat, lon));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static Optional<Instant> readMediaInstant(Path file, ZoneId photoZone) throws IOException {
        byte[] header = readHeaderBytes(file, 12);
        if (header.length >= 2 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8) {
            Optional<JpegTimestamp> jpegTime = readJpegTimestamp(file);
            if (jpegTime.isPresent()) {
                return Optional.of(jpegTime.get().toInstant(photoZone));
            }
        } else if (isMp4Header(header)) {
            Optional<Instant> mp4Time = readCreationInstantFromMp4(file);
            if (mp4Time.isPresent()) {
                return mp4Time;
            }
        }
        Optional<LocalDateTime> fromFilename = parseTimestampFromFilename(file.getFileName().toString());
        return fromFilename.map(local -> local.atZone(photoZone).toInstant());
    }

    private static final Pattern FILENAME_TIMESTAMP_PATTERN = Pattern.compile(
            "(?:^|[^\\d])(\\d{4})(\\d{2})(\\d{2})[_-]?(\\d{2})(\\d{2})(\\d{2})(?:[^\\d]|$)");

    static Optional<LocalDateTime> parseTimestampFromFilename(String filename) {
        Matcher matcher = FILENAME_TIMESTAMP_PATTERN.matcher(filename);
        if (!matcher.find()) return Optional.empty();
        try {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));
            int hour = Integer.parseInt(matcher.group(4));
            int minute = Integer.parseInt(matcher.group(5));
            int second = Integer.parseInt(matcher.group(6));
            if (year < 1970 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31
                    || hour > 23 || minute > 59 || second > 59) {
                return Optional.empty();
            }
            return Optional.of(LocalDateTime.of(year, month, day, hour, minute, second));
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    private static final long MAC_TO_UNIX_EPOCH_SECONDS = 2_082_844_800L;

    static Optional<Instant> readCreationInstantFromMp4(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            return findMvhdCreationTime(raf, 0L, raf.length(), 0);
        }
    }

    private static Optional<Instant> findMvhdCreationTime(RandomAccessFile raf, long start, long end, int depth) throws IOException {
        if (depth > 8) return Optional.empty();
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            long size = Integer.toUnsignedLong(raf.readInt());
            byte[] typeBytes = new byte[4];
            raf.readFully(typeBytes);
            long headerSize = 8;
            if (size == 1) {
                if (pos + 16 > end) return Optional.empty();
                size = raf.readLong();
                headerSize = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < headerSize) return Optional.empty();
            long dataStart = pos + headerSize;
            long boxEnd = pos + size;
            if (boxEnd > end || boxEnd < dataStart) return Optional.empty();

            String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
            if (type.equals("mvhd")) {
                return parseMvhdCreationTime(raf, dataStart, boxEnd);
            }
            if (type.equals("moov")) {
                Optional<Instant> nested = findMvhdCreationTime(raf, dataStart, boxEnd, depth + 1);
                if (nested.isPresent()) return nested;
            }
            pos = boxEnd;
        }
        return Optional.empty();
    }

    private static Optional<Instant> parseMvhdCreationTime(RandomAccessFile raf, long start, long end) throws IOException {
        if (end - start < 8) return Optional.empty();
        raf.seek(start);
        int version = raf.readUnsignedByte();
        raf.skipBytes(3);
        long creationMacSeconds;
        if (version == 1) {
            if (end - start < 32) return Optional.empty();
            creationMacSeconds = raf.readLong();
        } else {
            creationMacSeconds = Integer.toUnsignedLong(raf.readInt());
        }
        if (creationMacSeconds == 0) return Optional.empty();
        long unixSeconds = creationMacSeconds - MAC_TO_UNIX_EPOCH_SECONDS;
        return Optional.of(Instant.ofEpochSecond(unixSeconds));
    }

    record JpegTimestamp(LocalDateTime local, ZoneOffset offset) {
        Instant toInstant(ZoneId defaultZone) {
            if (offset != null) return local.toInstant(offset);
            return local.atZone(defaultZone).toInstant();
        }
    }

    static Optional<JpegTimestamp> readJpegTimestamp(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        int tiffStart = findExifTiffStart(data);
        if (tiffStart < 0) return Optional.empty();
        if (data.length - tiffStart < 8) return Optional.empty();
        boolean littleEndian;
        if (data[tiffStart] == 'I' && data[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (data[tiffStart] == 'M' && data[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return Optional.empty();
        }
        int magic = readShort(data, tiffStart + 2, littleEndian);
        if (magic != 42) return Optional.empty();
        long firstIfdOffset = readUnsignedLong(data, tiffStart + 4, littleEndian);
        int ifd0Pos = tiffStart + (int) firstIfdOffset;

        int exifSubIfdOffset = findIfdEntryValue(data, ifd0Pos, EXIF_SUB_IFD_TAG, littleEndian);
        if (exifSubIfdOffset < 0) return Optional.empty();
        int exifSubIfdPos = tiffStart + exifSubIfdOffset;

        String dateRaw = findAsciiValue(data, tiffStart, exifSubIfdPos, DATETIME_ORIGINAL_TAG, littleEndian);
        if (dateRaw == null || dateRaw.isBlank()) return Optional.empty();
        LocalDateTime local;
        try {
            local = LocalDateTime.parse(dateRaw.strip(), EXIF_DATETIME_FORMAT);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }

        ZoneOffset offset = null;
        String offsetRaw = findAsciiValue(data, tiffStart, exifSubIfdPos, OFFSET_TIME_ORIGINAL_TAG, littleEndian);
        if (offsetRaw != null && !offsetRaw.isBlank()) {
            try {
                offset = ZoneOffset.of(offsetRaw.strip());
            } catch (java.time.DateTimeException e) {
                // leave offset null
            }
        }
        return Optional.of(new JpegTimestamp(local, offset));
    }

    private static String findAsciiValue(byte[] data, int tiffStart, int ifdPos, int soughtTag, boolean littleEndian) {
        if (ifdPos + 2 > data.length) return null;
        int entryCount = readShort(data, ifdPos, littleEndian);
        for (int i = 0; i < entryCount; i++) {
            int entryPos = ifdPos + 2 + i * 12;
            if (entryPos + 12 > data.length) return null;
            int tag = readShort(data, entryPos, littleEndian);
            if (tag != soughtTag) continue;
            long count = readUnsignedLong(data, entryPos + 4, littleEndian);
            if (count == 0 || count > 4096) return null;
            int stringStart;
            if (count <= 4) {
                stringStart = entryPos + 8;
            } else {
                long offset = readUnsignedLong(data, entryPos + 8, littleEndian);
                stringStart = tiffStart + (int) offset;
            }
            if (stringStart < 0 || stringStart + count > data.length) return null;
            int usable = (int) count;
            while (usable > 0 && data[stringStart + usable - 1] == 0) usable--;
            return new String(data, stringStart, usable, StandardCharsets.US_ASCII);
        }
        return null;
    }

    static String formatGap(Duration gap) {
        long minutes = gap.toMinutes();
        long seconds = gap.minusMinutes(minutes).getSeconds();
        if (minutes == 0) return seconds + "s";
        if (seconds == 0) return minutes + " min";
        return minutes + " min " + seconds + "s";
    }

    record TimelinePoint(Instant time, double latitude, double longitude) {
    }

    record TimelineVisit(Instant start, Instant end, double latitude, double longitude) {
    }

    record TimelineMatch(TimelinePoint point, Duration gap, boolean fromVisit) {
    }

    static final class TimelineIndex {
        private static final Pattern POINT_TIME_PATTERN = Pattern.compile(
                "\"point\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"time\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern START_TIME_PATTERN = Pattern.compile(
                "\"startTime\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern END_TIME_PATTERN = Pattern.compile(
                "\"endTime\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern PLACE_LATLNG_PATTERN = Pattern.compile(
                "\"placeLocation\"\\s*:\\s*\\{\\s*\"latLng\"\\s*:\\s*\"([^\"]+)\"");

        private final List<TimelinePoint> pointsByTime;
        private final List<TimelineVisit> visitsByStart;

        private TimelineIndex(List<TimelinePoint> pointsByTime, List<TimelineVisit> visitsByStart) {
            this.pointsByTime = pointsByTime;
            this.visitsByStart = visitsByStart;
        }

        static TimelineIndex load(Path path) throws IOException {
            if (!Files.isRegularFile(path)) {
                throw new IOException("Timeline file not found: " + path);
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return fromJson(json);
        }

        static TimelineIndex fromJson(String json) {
            List<TimelinePoint> points = new ArrayList<>();
            List<TimelineVisit> visits = new ArrayList<>();

            for (String segment : extractSegments(json)) {
                Matcher pointMatcher = POINT_TIME_PATTERN.matcher(segment);
                while (pointMatcher.find()) {
                    TimelinePoint point = parsePointAndTime(pointMatcher.group(1), pointMatcher.group(2));
                    if (point != null) points.add(point);
                }
                if (segment.contains("\"visit\"")) {
                    Matcher startMatcher = START_TIME_PATTERN.matcher(segment);
                    Matcher endMatcher = END_TIME_PATTERN.matcher(segment);
                    Matcher latLngMatcher = PLACE_LATLNG_PATTERN.matcher(segment);
                    if (startMatcher.find() && endMatcher.find() && latLngMatcher.find()) {
                        TimelineVisit visit = parseVisit(startMatcher.group(1), endMatcher.group(1), latLngMatcher.group(1));
                        if (visit != null) visits.add(visit);
                    }
                }
            }

            points.sort((a, b) -> a.time().compareTo(b.time()));
            visits.sort((a, b) -> a.start().compareTo(b.start()));
            return new TimelineIndex(points, visits);
        }

        static List<String> extractSegments(String json) {
            List<String> segments = new ArrayList<>();
            int keyIdx = json.indexOf("\"semanticSegments\"");
            if (keyIdx < 0) return segments;
            int arrayStart = json.indexOf('[', keyIdx);
            if (arrayStart < 0) return segments;

            int depth = 0;
            boolean inString = false;
            boolean escape = false;
            int segStart = -1;

            for (int i = arrayStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escape) { escape = false; continue; }
                if (inString) {
                    if (c == '\\') escape = true;
                    else if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') { inString = true; continue; }
                if (c == '{') {
                    if (depth == 0) segStart = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && segStart >= 0) {
                        segments.add(json.substring(segStart, i + 1));
                        segStart = -1;
                    }
                } else if (c == ']' && depth == 0) {
                    break;
                }
            }
            return segments;
        }

        private static TimelinePoint parsePointAndTime(String pointText, String timeText) {
            double[] coords = parseCoordinates(pointText);
            if (coords == null) return null;
            try {
                Instant instant = OffsetDateTime.parse(timeText.trim()).toInstant();
                return new TimelinePoint(instant, coords[0], coords[1]);
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        private static TimelineVisit parseVisit(String startText, String endText, String latLngText) {
            double[] coords = parseCoordinates(latLngText);
            if (coords == null) return null;
            try {
                Instant start = OffsetDateTime.parse(startText.trim()).toInstant();
                Instant end = OffsetDateTime.parse(endText.trim()).toInstant();
                if (end.isBefore(start)) return null;
                return new TimelineVisit(start, end, coords[0], coords[1]);
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        private static double[] parseCoordinates(String value) {
            int comma = value.indexOf(',');
            if (comma < 0) return null;
            try {
                double lat = Double.parseDouble(stripDegree(value.substring(0, comma)));
                double lon = Double.parseDouble(stripDegree(value.substring(comma + 1)));
                return new double[]{lat, lon};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String stripDegree(String value) {
            return value.trim().replace("°", "").trim();
        }

        int pointCount() {
            return pointsByTime.size();
        }

        int visitCount() {
            return visitsByStart.size();
        }

        Optional<TimelineMatch> nearest(Instant instant, Duration maxGap) {
            for (TimelineVisit visit : visitsByStart) {
                if (visit.start().isAfter(instant)) break;
                if (!visit.end().isBefore(instant)) {
                    return Optional.of(new TimelineMatch(
                            new TimelinePoint(instant, visit.latitude(), visit.longitude()),
                            Duration.ZERO,
                            true));
                }
            }

            if (pointsByTime.isEmpty()) return Optional.empty();
            int idx = Collections.binarySearch(pointsByTime, new TimelinePoint(instant, 0, 0),
                    (a, b) -> a.time().compareTo(b.time()));
            if (idx < 0) idx = -idx - 1;

            TimelinePoint best = null;
            Duration bestGap = null;
            for (int candidate : new int[]{idx - 1, idx}) {
                if (candidate < 0 || candidate >= pointsByTime.size()) continue;
                TimelinePoint p = pointsByTime.get(candidate);
                Duration gap = Duration.between(p.time(), instant);
                Duration abs = gap.abs();
                if (bestGap == null || abs.compareTo(bestGap) < 0) {
                    best = p;
                    bestGap = gap;
                }
            }
            if (best == null) return Optional.empty();
            if (bestGap.abs().compareTo(maxGap) > 0) return Optional.empty();
            return Optional.of(new TimelineMatch(best, bestGap, false));
        }
    }

    Optional<String> reverseGeocode(GpsFix fix, String userAgent, Duration timeout, String acceptLanguage) throws IOException, InterruptedException {
        String url = NOMINATIM_HOST + "/reverse"
                + "?format=jsonv2"
                + "&lat=" + URLEncoder.encode(String.format(Locale.ROOT, "%.6f", fix.latitude()), StandardCharsets.UTF_8)
                + "&lon=" + URLEncoder.encode(String.format(Locale.ROOT, "%.6f", fix.longitude()), StandardCharsets.UTF_8)
                + "&zoom=18&addressdetails=1";

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET();
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            requestBuilder.header("Accept-Language", acceptLanguage);
        }
        HttpRequest request = requestBuilder.build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new IOException("Nominatim request timed out after " + timeout.toSeconds() + "s.", e);
        } catch (IOException e) {
            throw new IOException("Could not contact Nominatim: " + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Nominatim returned HTTP " + response.statusCode() + ".");
        }
        return composeLocation(response.body());
    }

    static List<String> locationParts(String body) {
        String road = JsonHelpers.jsonString(body, "road");
        String hamlet = firstNonBlank(
                JsonHelpers.jsonString(body, "hamlet"),
                JsonHelpers.jsonString(body, "village"),
                JsonHelpers.jsonString(body, "neighbourhood"),
                JsonHelpers.jsonString(body, "suburb"),
                JsonHelpers.jsonString(body, "quarter"));
        String town = firstNonBlank(
                JsonHelpers.jsonString(body, "town"),
                JsonHelpers.jsonString(body, "city"),
                JsonHelpers.jsonString(body, "municipality"),
                JsonHelpers.jsonString(body, "county"));

        List<String> parts = new ArrayList<>();
        if (!town.isEmpty()) parts.add(town);
        if (!hamlet.isEmpty() && !hamlet.equalsIgnoreCase(town)) parts.add(hamlet);
        if (!road.isEmpty()) parts.add(road);

        if (parts.stream().anyMatch(PrintMediaLocation::hasLatinLetter)) {
            parts.removeIf(part -> !hasLatinLetter(part));
        }
        return parts;
    }

    static Optional<String> composeLocation(String body) {
        List<String> parts = locationParts(body);
        if (parts.isEmpty()) {
            String displayName = JsonHelpers.jsonString(body, "display_name");
            return displayName.isEmpty() ? Optional.empty() : Optional.of(displayName);
        }
        return Optional.of(String.join(", ", parts));
    }

    static String suggestFilename(Path original, List<String> parts) {
        String name = original.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        StringBuilder sb = new StringBuilder(stem);
        for (String part : parts) {
            String cleaned = toFilenamePart(part);
            if (!cleaned.isBlank()) {
                sb.append('_').append(cleaned);
            }
        }
        return sb.append(ext).toString();
    }

    static String toFilenamePart(String value) {
        String cleaned = value
                .replace('/', '-')
                .replace('\\', '-')
                .replace(':', '-')
                .replace('*', '-')
                .replace('?', '-')
                .replace('"', '-')
                .replace('<', '-')
                .replace('>', '-')
                .replace('|', '-')
                .replace(' ', '-')
                .replaceAll("-+", "-");
        while (cleaned.startsWith("-")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith("-")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    static boolean hasLatinLetter(String value) {
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new UsageException("Missing value for " + option + ".");
        }
        return args[index].trim();
    }

    private static String valueAfterEquals(String arg, String option) {
        String value = arg.substring(option.length() + 1).trim();
        if (value.isBlank()) {
            throw new UsageException("Missing value for " + option + ".");
        }
        return value;
    }

    private static Path expandHomePath(String value) {
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home") + value.substring(1));
        }
        return Path.of(value);
    }

    private static int parseBoundedInt(String value, String option, int min, int max) {
        try {
            int number = Integer.parseInt(value);
            if (number < min || number > max) {
                throw new UsageException(option + " must be between " + min + " and " + max + ".");
            }
            return number;
        } catch (NumberFormatException e) {
            throw new UsageException(option + " must be a number.");
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java --enable-preview src/main/java/net/lckx/describe/PrintMediaLocation.java <image-or-video-file> [options]

                Options:
                  --no-geocode                    Only print GPS coordinates; do not query Nominatim
                  --user-agent <text>             User-Agent header sent to Nominatim
                  --timeout-seconds <n>           Nominatim request timeout. Default: 15, max: 300
                  --timeline <path>               Google Timeline JSON to fall back to when the file
                                                  has no GPS. Reads the photo's EXIF timestamp and
                                                  looks up the nearest recorded point.
                  --timeline-max-gap-minutes <n>  How far a Timeline point may be from the photo's
                                                  timestamp before it is rejected. Default: 30
                  --photo-zone <zone-id>          Timezone assumed for the photo's timestamp when
                                                  EXIF has no offset. Default: system timezone.
                  --language <accept-language>    Preferred language for Nominatim place names,
                                                  e.g. "en", "nl", or "nl,en;q=0.5". Default:
                                                  system language with English fallback.
                  --help                          Show this help

                Nominatim is a free OpenStreetMap-based reverse geocoder. Please respect
                their usage policy (https://operations.osmfoundation.org/policies/nominatim/).

                Example:
                  java --enable-preview src/main/java/net/lckx/describe/PrintMediaLocation.java ~/Pictures/photo.jpg
                """);
    }

    record Options(Path file, boolean reverseGeocode, String userAgent, Duration timeout,
                   Path timelinePath, Duration timelineMaxGap, ZoneId photoZone,
                   boolean timelineAutoDetected, String acceptLanguage, boolean suggestFilename) {
        Options withFile(Path newFile) {
            return new Options(newFile, reverseGeocode, userAgent, timeout, timelinePath,
                    timelineMaxGap, photoZone, timelineAutoDetected, acceptLanguage, suggestFilename);
        }
    }

    record GpsFix(double latitude, double longitude) {
    }

    static class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    static class HelpException extends UsageException {
        HelpException() {
            super("PrintMediaLocation help");
        }
    }
}
