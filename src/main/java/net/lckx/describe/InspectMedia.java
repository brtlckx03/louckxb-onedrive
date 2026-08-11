package net.lckx.describe;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Prints everything the toolkit can read from a photo or video: file info, JPEG segments,
 * EXIF tags with human-readable names, GPS coordinates, MP4 box tree, timestamps, etc.
 */
public class InspectMedia {

    public static void main(String[] args) {
        int exitCode = new InspectMedia().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        try {
            Options options = parseOptions(args);
            if (options.file() == null) {
                options = options.withFile(promptForFile(new Scanner(System.in)));
            }
            Path file = options.file();
            if (!Files.isRegularFile(file)) {
                throw new UsageException("Not a regular file: " + file);
            }

            long size = Files.size(file);
            System.out.println("File:      " + file.toAbsolutePath());
            System.out.println(String.format(Locale.ROOT, "Size:      %.2f MB (%d bytes)",
                    size / 1024.0 / 1024.0, size));

            byte[] head = readHead(file, 12);
            if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
                inspectJpeg(file);
            } else if (isMp4Header(head)) {
                inspectMp4(file);
            } else {
                System.out.println("Type:      (unknown — not a JPEG or MP4)");
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
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static boolean isMp4Header(byte[] head) {
        return head.length >= 8
                && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
    }

    private static byte[] readHead(Path file, int count) throws IOException {
        byte[] buf = new byte[count];
        try (var in = Files.newInputStream(file)) {
            int read = in.readNBytes(buf, 0, count);
            if (read < count) {
                byte[] shorter = new byte[read];
                System.arraycopy(buf, 0, shorter, 0, read);
                return shorter;
            }
        }
        return buf;
    }

    // --------------------------------------------------------------------- JPEG

    private void inspectJpeg(Path file) throws IOException {
        System.out.println("Type:      JPEG");
        Dim dim = readJpegDim(file);
        if (dim != null) {
            System.out.println("Dimensions:" + dim.w() + " x " + dim.h() + " px");
        }
        byte[] data = Files.readAllBytes(file);
        System.out.println();
        System.out.println("JPEG segments:");
        List<Segment> segments = walkJpegSegments(data);
        for (Segment seg : segments) {
            System.out.println(String.format(Locale.ROOT, "  0x%02X %-8s (%d bytes)%s",
                    seg.marker(), seg.name(), seg.length(),
                    seg.header().isEmpty() ? "" : "  " + seg.header()));
        }

        int tiffStart = findExifTiffStart(data);
        if (tiffStart < 0) {
            System.out.println();
            System.out.println("EXIF: none");
            return;
        }

        boolean littleEndian = data[tiffStart] == 'I' && data[tiffStart + 1] == 'I';
        int magic = readShort(data, tiffStart + 2, littleEndian);
        long firstIfdOffset = readUnsignedLong(data, tiffStart + 4, littleEndian);
        System.out.println();
        System.out.println("EXIF (" + (littleEndian ? "II" : "MM") + ", magic=" + magic
                + ", byte-order=" + (littleEndian ? "little-endian" : "big-endian") + "):");

        int ifd0Pos = tiffStart + (int) firstIfdOffset;
        int nextIfdOffset = printIfd(data, tiffStart, ifd0Pos, littleEndian, "IFD0 (main image)", IFD0_TAGS);

        int subIfd = findValue(data, ifd0Pos, EXIF_SUB_IFD_POINTER, littleEndian);
        if (subIfd > 0) {
            printIfd(data, tiffStart, tiffStart + subIfd, littleEndian, "Exif SubIFD", EXIF_TAGS);
        }
        int gpsIfd = findValue(data, ifd0Pos, GPS_INFO_POINTER, littleEndian);
        if (gpsIfd > 0) {
            printIfd(data, tiffStart, tiffStart + gpsIfd, littleEndian, "GPS Info", GPS_TAGS);
            printDecodedGps(data, tiffStart, tiffStart + gpsIfd, littleEndian);
        }
        if (nextIfdOffset > 0) {
            printIfd(data, tiffStart, tiffStart + nextIfdOffset, littleEndian, "IFD1 (thumbnail)", IFD0_TAGS);
        }
    }

    private static int printIfd(byte[] data, int tiffStart, int ifdPos, boolean le,
                                String label, Map<Integer, String> tagNames) {
        System.out.println();
        System.out.println("  " + label + " @ 0x" + Integer.toHexString(ifdPos - tiffStart) + ":");
        if (ifdPos + 2 > data.length) {
            System.out.println("    (truncated)");
            return 0;
        }
        int entryCount = readShort(data, ifdPos, le);
        for (int i = 0; i < entryCount; i++) {
            int entryPos = ifdPos + 2 + i * 12;
            if (entryPos + 12 > data.length) break;
            int tag = readShort(data, entryPos, le);
            int type = readShort(data, entryPos + 2, le);
            long count = readUnsignedLong(data, entryPos + 4, le);
            String name = tagNames.getOrDefault(tag, String.format("0x%04X", tag));
            String value = formatValue(data, tiffStart, entryPos + 8, type, count, le);
            String decoded = decodeEnum(tag, tagNames, value);
            System.out.println(String.format(Locale.ROOT, "    %-24s %s%s", name, value,
                    decoded.isEmpty() ? "" : "  (" + decoded + ")"));
        }
        int nextIfdOffsetPos = ifdPos + 2 + entryCount * 12;
        if (nextIfdOffsetPos + 4 > data.length) return 0;
        return (int) readUnsignedLong(data, nextIfdOffsetPos, le);
    }

    private static void printDecodedGps(byte[] data, int tiffStart, int gpsIfdPos, boolean le) {
        int entryCount = readShort(data, gpsIfdPos, le);
        Map<Integer, byte[]> entries = new HashMap<>();
        Map<Integer, Long> counts = new HashMap<>();
        Map<Integer, Integer> types = new HashMap<>();
        for (int i = 0; i < entryCount; i++) {
            int entryPos = gpsIfdPos + 2 + i * 12;
            int tag = readShort(data, entryPos, le);
            int type = readShort(data, entryPos + 2, le);
            long count = readUnsignedLong(data, entryPos + 4, le);
            byte[] valueField = new byte[4];
            System.arraycopy(data, entryPos + 8, valueField, 0, 4);
            entries.put(tag, valueField);
            counts.put(tag, count);
            types.put(tag, type);
        }
        String latRef = readAsciiInline(entries.get(0x0001));
        String lonRef = readAsciiInline(entries.get(0x0003));
        double[] latDms = readRationalTriplet(data, tiffStart, entries.get(0x0002), counts.get(0x0002), le);
        double[] lonDms = readRationalTriplet(data, tiffStart, entries.get(0x0004), counts.get(0x0004), le);
        if (latRef != null && lonRef != null && latDms != null && lonDms != null) {
            double lat = latDms[0] + latDms[1] / 60.0 + latDms[2] / 3600.0;
            double lon = lonDms[0] + lonDms[1] / 60.0 + lonDms[2] / 3600.0;
            if (latRef.startsWith("S")) lat = -lat;
            if (lonRef.startsWith("W")) lon = -lon;
            System.out.println();
            System.out.println("  GPS decoded:");
            System.out.println(String.format(Locale.ROOT, "    Latitude       %.6f°  (%s)", lat, latRef));
            System.out.println(String.format(Locale.ROOT, "    Longitude      %.6f°  (%s)", lon, lonRef));
        }
    }

    private static double[] readRationalTriplet(byte[] data, int tiffStart, byte[] valueField, Long count, boolean le) {
        if (valueField == null || count == null || count < 3) return null;
        int offset = (int) readUnsignedLongBytes(valueField, le);
        int pos = tiffStart + offset;
        if (pos < 0 || pos + 24 > data.length) return null;
        double[] result = new double[3];
        for (int i = 0; i < 3; i++) {
            long num = readUnsignedLong(data, pos + i * 8, le);
            long den = readUnsignedLong(data, pos + i * 8 + 4, le);
            result[i] = den == 0 ? 0.0 : (double) num / (double) den;
        }
        return result;
    }

    private static String readAsciiInline(byte[] valueField) {
        if (valueField == null) return null;
        StringBuilder sb = new StringBuilder();
        for (byte b : valueField) {
            if (b == 0) break;
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
    }

    private static String formatValue(byte[] data, int tiffStart, int valueFieldPos,
                                      int type, long count, boolean le) {
        int bytesPerElement = switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12 -> 8;
            default -> 1;
        };
        int totalBytes = (int) (bytesPerElement * count);
        int start = valueFieldPos;
        if (totalBytes > 4) {
            long offset = readUnsignedLong(data, valueFieldPos, le);
            start = tiffStart + (int) offset;
        }
        if (start < 0 || start + totalBytes > data.length) return "(out of range)";

        return switch (type) {
            case 1, 7 -> formatBytes(data, start, (int) count);
            case 2 -> formatAscii(data, start, (int) count);
            case 3 -> formatShorts(data, start, (int) count, le);
            case 4 -> formatLongs(data, start, (int) count, le);
            case 5 -> formatRationals(data, start, (int) count, le, false);
            case 9 -> formatSlongs(data, start, (int) count, le);
            case 10 -> formatRationals(data, start, (int) count, le, true);
            default -> "type=" + type + " count=" + count;
        };
    }

    private static String formatBytes(byte[] data, int start, int count) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(count, 16);
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[start + i] & 0xFF));
        }
        if (count > max) sb.append(" …");
        return sb.toString();
    }

    private static String formatAscii(byte[] data, int start, int count) {
        int usable = count;
        while (usable > 0 && data[start + usable - 1] == 0) usable--;
        return "\"" + new String(data, start, usable, StandardCharsets.US_ASCII) + "\"";
    }

    private static String formatShorts(byte[] data, int start, int count, boolean le) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append(readShort(data, start + i * 2, le));
            if (i >= 8 && count > 10) { sb.append(", … (").append(count).append(" total)"); break; }
        }
        return sb.toString();
    }

    private static String formatLongs(byte[] data, int start, int count, boolean le) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append(readUnsignedLong(data, start + i * 4, le));
            if (i >= 4 && count > 6) { sb.append(", … (").append(count).append(" total)"); break; }
        }
        return sb.toString();
    }

    private static String formatSlongs(byte[] data, int start, int count, boolean le) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append((int) readUnsignedLong(data, start + i * 4, le));
            if (i >= 4 && count > 6) { sb.append(", … (").append(count).append(" total)"); break; }
        }
        return sb.toString();
    }

    private static String formatRationals(byte[] data, int start, int count, boolean le, boolean signed) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            long num = readUnsignedLong(data, start + i * 8, le);
            long den = readUnsignedLong(data, start + i * 8 + 4, le);
            if (signed) {
                num = (int) num;
                den = (int) den;
            }
            if (den == 0) {
                sb.append(num).append("/0");
            } else {
                double val = (double) num / (double) den;
                if (num % den == 0) {
                    sb.append(num / den);
                } else {
                    sb.append(String.format(Locale.ROOT, "%d/%d (%.4f)", num, den, val));
                }
            }
            if (i >= 3 && count > 5) { sb.append(", … (").append(count).append(" total)"); break; }
        }
        return sb.toString();
    }

    private static String decodeEnum(int tag, Map<Integer, String> ifdMap, String value) {
        if (!ifdMap.containsKey(tag)) return "";
        if (tag == 0x0112) return ORIENTATIONS.getOrDefault(parseInt(value), "");
        if (tag == 0x8822) return EXPOSURE_PROGRAMS.getOrDefault(parseInt(value), "");
        if (tag == 0x9207) return METERING_MODES.getOrDefault(parseInt(value), "");
        if (tag == 0x9208) return LIGHT_SOURCES.getOrDefault(parseInt(value), "");
        if (tag == 0x9209) return FLASH_VALUES.getOrDefault(parseInt(value), "");
        if (tag == 0xa001) return COLOR_SPACES.getOrDefault(parseInt(value), "");
        if (tag == 0xa402) return EXPOSURE_MODES.getOrDefault(parseInt(value), "");
        if (tag == 0xa403) return WHITE_BALANCES.getOrDefault(parseInt(value), "");
        if (tag == 0xa406) return SCENE_CAPTURE_TYPES.getOrDefault(parseInt(value), "");
        return "";
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return -1; }
    }

    // ---------------------------------------------------------------------- MP4

    private void inspectMp4(Path file) throws IOException {
        System.out.println("Type:      MP4/MOV");
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            System.out.println();
            System.out.println("Box tree:");
            printBoxes(raf, 0L, raf.length(), 0);
        }
    }

    private void printBoxes(RandomAccessFile raf, long start, long end, int depth) throws IOException {
        if (depth > 12) return;
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            long size = Integer.toUnsignedLong(raf.readInt());
            byte[] typeBytes = new byte[4];
            raf.readFully(typeBytes);
            long headerSize = 8;
            if (size == 1) {
                if (pos + 16 > end) return;
                size = raf.readLong();
                headerSize = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < headerSize) return;
            long dataStart = pos + headerSize;
            long boxEnd = pos + size;
            if (boxEnd > end || boxEnd < dataStart) return;

            String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
            String indent = "  ".repeat(depth + 1);
            String extra = decodeBox(raf, type, dataStart, boxEnd);
            System.out.println(String.format(Locale.ROOT, "%s[%s] %d bytes%s",
                    indent, printableType(type), size, extra.isEmpty() ? "" : "  " + extra));

            if (isContainer(type)) {
                long childStart = type.equals("meta") ? dataStart + 4 : dataStart;
                printBoxes(raf, childStart, boxEnd, depth + 1);
            }
            pos = boxEnd;
        }
    }

    private static String printableType(String type) {
        StringBuilder sb = new StringBuilder(4);
        for (char c : type.toCharArray()) {
            if (c < 0x20 || c > 0x7E) {
                sb.append(String.format("\\x%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isContainer(String type) {
        return switch (type) {
            case "moov", "trak", "edts", "mdia", "minf", "dinf", "stbl", "udta", "meta", "ilst" -> true;
            default -> false;
        };
    }

    private String decodeBox(RandomAccessFile raf, String type, long start, long end) throws IOException {
        long len = end - start;
        if (len <= 0) return "";
        switch (type) {
            case "ftyp": {
                if (len < 4) return "";
                raf.seek(start);
                byte[] major = new byte[4];
                raf.readFully(major);
                return "major=" + new String(major, StandardCharsets.ISO_8859_1);
            }
            case "mvhd": {
                if (len < 20) return "";
                raf.seek(start);
                int versionAndFlags = raf.readInt();
                int version = (versionAndFlags >>> 24) & 0xFF;
                long created;
                if (version == 1) {
                    if (len < 32) return "";
                    created = raf.readLong();
                } else {
                    created = Integer.toUnsignedLong(raf.readInt());
                }
                if (created == 0) return "";
                Instant instant = Instant.ofEpochSecond(created - 2_082_844_800L);
                return "created=" + instant;
            }
            case "©xyz":
            case "loci": {
                if (len < 4) return "";
                raf.seek(start);
                byte[] payload = new byte[(int) Math.min(len, 512)];
                raf.readFully(payload);
                int textStart = type.equals("©xyz") ? 4 : 0;
                if (textStart >= payload.length) return "";
                String text = new String(payload, textStart, payload.length - textStart, StandardCharsets.UTF_8).trim();
                return "location=" + text.replaceAll("\\p{C}", " ").trim();
            }
            default:
                return "";
        }
    }

    // --------------------------------------------------------------------- Options

    static Options parseOptions(String[] args) {
        Path file = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                throw new HelpException();
            } else if (arg.startsWith("-")) {
                throw new UsageException("Unknown option: " + arg);
            } else if (file == null) {
                file = expandHomePath(arg);
            } else {
                throw new UsageException("Only one file can be inspected at a time.");
            }
        }
        return new Options(file);
    }

    private static Path promptForFile(Scanner scanner) {
        while (true) {
            System.out.print("Media file: ");
            System.out.flush();
            if (!scanner.hasNextLine()) throw new UsageException("Missing media file.");
            String input = scanner.nextLine().trim();
            if (input.length() >= 2 && ((input.startsWith("\"") && input.endsWith("\""))
                    || (input.startsWith("'") && input.endsWith("'")))) {
                input = input.substring(1, input.length() - 1).trim();
            }
            if (!input.isEmpty()) return expandHomePath(input);
            System.out.println("Please enter a path to a file (or press Ctrl+C to cancel).");
        }
    }

    private static Path expandHomePath(String value) {
        if (value.equals("~")) return Path.of(System.getProperty("user.home"));
        if (value.startsWith("~/")) return Path.of(System.getProperty("user.home") + value.substring(1));
        return Path.of(value);
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java -cp target/classes net.lckx.describe.InspectMedia <file>

                Prints everything the toolkit knows about a photo or video:
                  - File name, size, dimensions
                  - JPEG segment map (SOI, APP0/1, DQT, SOF, DHT, SOS, EOI, ...)
                  - EXIF IFD0, Exif SubIFD, GPS IFD, IFD1 (thumbnail) with tag names
                  - Decoded GPS decimal degrees when GPS is present
                  - MP4/MOV box tree with sizes and known metadata
                """);
    }

    // --------------------------------------------------------------------- Helpers

    private static int findExifTiffStart(byte[] data) {
        if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) return -1;
        int pos = 2;
        while (pos + 3 < data.length) {
            if ((data[pos] & 0xFF) != 0xFF) return -1;
            int marker = data[pos + 1] & 0xFF;
            pos += 2;
            if (marker == 0xD9 || marker == 0xDA) return -1;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;
            if (pos + 2 > data.length) return -1;
            int segLength = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            int segStart = pos + 2;
            int segEnd = pos + segLength;
            if (segLength < 2 || segEnd > data.length) return -1;
            if (marker == 0xE1 && segEnd - segStart >= 6
                    && data[segStart] == 'E' && data[segStart + 1] == 'x'
                    && data[segStart + 2] == 'i' && data[segStart + 3] == 'f'
                    && data[segStart + 4] == 0 && data[segStart + 5] == 0) {
                return segStart + 6;
            }
            pos = segEnd;
        }
        return -1;
    }

    private static int findValue(byte[] data, int ifdPos, int soughtTag, boolean le) {
        if (ifdPos + 2 > data.length) return -1;
        int entryCount = readShort(data, ifdPos, le);
        for (int i = 0; i < entryCount; i++) {
            int entryPos = ifdPos + 2 + i * 12;
            if (entryPos + 12 > data.length) return -1;
            int tag = readShort(data, entryPos, le);
            if (tag == soughtTag) return (int) readUnsignedLong(data, entryPos + 8, le);
        }
        return -1;
    }

    private static int readShort(byte[] data, int pos, boolean le) {
        int b0 = data[pos] & 0xFF, b1 = data[pos + 1] & 0xFF;
        return le ? (b1 << 8) | b0 : (b0 << 8) | b1;
    }

    private static long readUnsignedLong(byte[] data, int pos, boolean le) {
        long b0 = data[pos] & 0xFF, b1 = data[pos + 1] & 0xFF;
        long b2 = data[pos + 2] & 0xFF, b3 = data[pos + 3] & 0xFF;
        return le ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
                : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static long readUnsignedLongBytes(byte[] value4, boolean le) {
        long b0 = value4[0] & 0xFF, b1 = value4[1] & 0xFF;
        long b2 = value4[2] & 0xFF, b3 = value4[3] & 0xFF;
        return le ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
                : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static Dim readJpegDim(Path file) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            ImageReader r = readers.next();
            try {
                r.setInput(stream);
                return new Dim(r.getWidth(0), r.getHeight(0));
            } finally {
                r.dispose();
            }
        }
    }

    private static List<Segment> walkJpegSegments(byte[] data) {
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(0xD8, "SOI", 2, ""));
        int pos = 2;
        while (pos + 1 < data.length) {
            if ((data[pos] & 0xFF) != 0xFF) break;
            int marker = data[pos + 1] & 0xFF;
            if (marker == 0xD9) { segments.add(new Segment(marker, "EOI", 2, "")); break; }
            if (marker == 0xDA) { segments.add(new Segment(marker, "SOS", data.length - pos, "compressed image data follows")); break; }
            pos += 2;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;
            if (pos + 2 > data.length) break;
            int segLength = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            String header = "";
            if (marker == 0xE1 && segLength >= 8 && data[pos + 2] == 'E') {
                header = new String(data, pos + 2, Math.min(6, segLength - 2), StandardCharsets.ISO_8859_1);
            } else if (marker == 0xE0 && segLength >= 7) {
                header = new String(data, pos + 2, Math.min(5, segLength - 2), StandardCharsets.ISO_8859_1);
            } else if (marker == 0xE2 && segLength >= 8) {
                header = new String(data, pos + 2, Math.min(12, segLength - 2), StandardCharsets.ISO_8859_1);
            } else if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (segLength >= 8) {
                    int precision = data[pos + 2] & 0xFF;
                    int h = ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);
                    int w = ((data[pos + 5] & 0xFF) << 8) | (data[pos + 6] & 0xFF);
                    header = "SOF baseline, " + w + "x" + h + " @ " + precision + "-bit";
                }
            }
            segments.add(new Segment(marker, markerName(marker), 2 + segLength, header.strip()));
            pos += segLength;
        }
        return segments;
    }

    private static String markerName(int m) {
        if (m == 0xE0) return "APP0";
        if (m == 0xE1) return "APP1";
        if (m == 0xE2) return "APP2";
        if (m >= 0xE0 && m <= 0xEF) return "APP" + (m - 0xE0);
        if (m == 0xDB) return "DQT";
        if (m == 0xC4) return "DHT";
        if (m == 0xDA) return "SOS";
        if (m == 0xD8) return "SOI";
        if (m == 0xD9) return "EOI";
        if (m == 0xDD) return "DRI";
        if (m == 0xFE) return "COM";
        if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return "SOF" + (m - 0xC0);
        return String.format("0x%02X", m);
    }

    // Records / constants

    record Dim(int w, int h) {
    }

    record Segment(int marker, String name, long length, String header) {
    }

    record Options(Path file) {
        Options withFile(Path newFile) { return new Options(newFile); }
    }

    static class UsageException extends RuntimeException {
        UsageException(String message) { super(message); }
    }

    static class HelpException extends UsageException {
        HelpException() { super("InspectMedia help"); }
    }

    private static final int EXIF_SUB_IFD_POINTER = 0x8769;
    private static final int GPS_INFO_POINTER = 0x8825;

    static final Map<Integer, String> IFD0_TAGS = Map.ofEntries(
            Map.entry(0x0100, "ImageWidth"),
            Map.entry(0x0101, "ImageLength"),
            Map.entry(0x0102, "BitsPerSample"),
            Map.entry(0x0103, "Compression"),
            Map.entry(0x0106, "PhotometricInterpretation"),
            Map.entry(0x010e, "ImageDescription"),
            Map.entry(0x010f, "Make"),
            Map.entry(0x0110, "Model"),
            Map.entry(0x0111, "StripOffsets"),
            Map.entry(0x0112, "Orientation"),
            Map.entry(0x0115, "SamplesPerPixel"),
            Map.entry(0x0116, "RowsPerStrip"),
            Map.entry(0x0117, "StripByteCounts"),
            Map.entry(0x011a, "XResolution"),
            Map.entry(0x011b, "YResolution"),
            Map.entry(0x011c, "PlanarConfiguration"),
            Map.entry(0x0128, "ResolutionUnit"),
            Map.entry(0x0131, "Software"),
            Map.entry(0x0132, "DateTime"),
            Map.entry(0x013b, "Artist"),
            Map.entry(0x013e, "WhitePoint"),
            Map.entry(0x013f, "PrimaryChromaticities"),
            Map.entry(0x0201, "JPEGInterchangeFormat"),
            Map.entry(0x0202, "JPEGInterchangeFormatLength"),
            Map.entry(0x0211, "YCbCrCoefficients"),
            Map.entry(0x0213, "YCbCrPositioning"),
            Map.entry(0x0214, "ReferenceBlackWhite"),
            Map.entry(0x8298, "Copyright"),
            Map.entry(0x8769, "ExifIFDPointer"),
            Map.entry(0x8825, "GPSInfoIFDPointer")
    );

    static final Map<Integer, String> EXIF_TAGS = Map.ofEntries(
            Map.entry(0x829a, "ExposureTime"),
            Map.entry(0x829d, "FNumber"),
            Map.entry(0x8822, "ExposureProgram"),
            Map.entry(0x8827, "ISOSpeedRatings"),
            Map.entry(0x9000, "ExifVersion"),
            Map.entry(0x9003, "DateTimeOriginal"),
            Map.entry(0x9004, "DateTimeDigitized"),
            Map.entry(0x9010, "OffsetTime"),
            Map.entry(0x9011, "OffsetTimeOriginal"),
            Map.entry(0x9012, "OffsetTimeDigitized"),
            Map.entry(0x9101, "ComponentsConfiguration"),
            Map.entry(0x9102, "CompressedBitsPerPixel"),
            Map.entry(0x9201, "ShutterSpeedValue"),
            Map.entry(0x9202, "ApertureValue"),
            Map.entry(0x9203, "BrightnessValue"),
            Map.entry(0x9204, "ExposureBiasValue"),
            Map.entry(0x9205, "MaxApertureValue"),
            Map.entry(0x9206, "SubjectDistance"),
            Map.entry(0x9207, "MeteringMode"),
            Map.entry(0x9208, "LightSource"),
            Map.entry(0x9209, "Flash"),
            Map.entry(0x920a, "FocalLength"),
            Map.entry(0x9214, "SubjectArea"),
            Map.entry(0x927c, "MakerNote"),
            Map.entry(0x9286, "UserComment"),
            Map.entry(0x9290, "SubSecTime"),
            Map.entry(0x9291, "SubSecTimeOriginal"),
            Map.entry(0x9292, "SubSecTimeDigitized"),
            Map.entry(0xa000, "FlashpixVersion"),
            Map.entry(0xa001, "ColorSpace"),
            Map.entry(0xa002, "PixelXDimension"),
            Map.entry(0xa003, "PixelYDimension"),
            Map.entry(0xa005, "InteropIFDPointer"),
            Map.entry(0xa20e, "FocalPlaneXResolution"),
            Map.entry(0xa20f, "FocalPlaneYResolution"),
            Map.entry(0xa210, "FocalPlaneResolutionUnit"),
            Map.entry(0xa215, "ExposureIndex"),
            Map.entry(0xa217, "SensingMethod"),
            Map.entry(0xa300, "FileSource"),
            Map.entry(0xa301, "SceneType"),
            Map.entry(0xa302, "CFAPattern"),
            Map.entry(0xa401, "CustomRendered"),
            Map.entry(0xa402, "ExposureMode"),
            Map.entry(0xa403, "WhiteBalance"),
            Map.entry(0xa404, "DigitalZoomRatio"),
            Map.entry(0xa405, "FocalLengthIn35mmFilm"),
            Map.entry(0xa406, "SceneCaptureType"),
            Map.entry(0xa407, "GainControl"),
            Map.entry(0xa408, "Contrast"),
            Map.entry(0xa409, "Saturation"),
            Map.entry(0xa40a, "Sharpness"),
            Map.entry(0xa432, "LensSpecification"),
            Map.entry(0xa433, "LensMake"),
            Map.entry(0xa434, "LensModel")
    );

    static final Map<Integer, String> GPS_TAGS = Map.ofEntries(
            Map.entry(0x0000, "GPSVersionID"),
            Map.entry(0x0001, "GPSLatitudeRef"),
            Map.entry(0x0002, "GPSLatitude"),
            Map.entry(0x0003, "GPSLongitudeRef"),
            Map.entry(0x0004, "GPSLongitude"),
            Map.entry(0x0005, "GPSAltitudeRef"),
            Map.entry(0x0006, "GPSAltitude"),
            Map.entry(0x0007, "GPSTimeStamp"),
            Map.entry(0x0008, "GPSSatellites"),
            Map.entry(0x0009, "GPSStatus"),
            Map.entry(0x000a, "GPSMeasureMode"),
            Map.entry(0x000b, "GPSDOP"),
            Map.entry(0x000c, "GPSSpeedRef"),
            Map.entry(0x000d, "GPSSpeed"),
            Map.entry(0x000e, "GPSTrackRef"),
            Map.entry(0x000f, "GPSTrack"),
            Map.entry(0x0010, "GPSImgDirectionRef"),
            Map.entry(0x0011, "GPSImgDirection"),
            Map.entry(0x0012, "GPSMapDatum"),
            Map.entry(0x001d, "GPSDateStamp"),
            Map.entry(0x001f, "GPSHPositioningError")
    );

    static final Map<Integer, String> ORIENTATIONS = Map.of(
            1, "Horizontal (normal)",
            2, "Mirror horizontal",
            3, "Rotate 180",
            4, "Mirror vertical",
            5, "Mirror horizontal & rotate 270 CW",
            6, "Rotate 90 CW",
            7, "Mirror horizontal & rotate 90 CW",
            8, "Rotate 270 CW"
    );

    static final Map<Integer, String> EXPOSURE_PROGRAMS = Map.of(
            0, "Not defined",
            1, "Manual",
            2, "Normal program",
            3, "Aperture priority",
            4, "Shutter priority",
            5, "Creative (biased toward depth of field)",
            6, "Action (biased toward fast shutter speed)",
            7, "Portrait",
            8, "Landscape"
    );

    static final Map<Integer, String> METERING_MODES = Map.of(
            0, "Unknown",
            1, "Average",
            2, "Center-weighted average",
            3, "Spot",
            4, "Multi-spot",
            5, "Pattern",
            6, "Partial"
    );

    static final Map<Integer, String> LIGHT_SOURCES = Map.ofEntries(
            Map.entry(0, "Unknown"),
            Map.entry(1, "Daylight"),
            Map.entry(2, "Fluorescent"),
            Map.entry(3, "Tungsten"),
            Map.entry(4, "Flash"),
            Map.entry(9, "Fine weather"),
            Map.entry(10, "Cloudy weather"),
            Map.entry(11, "Shade")
    );

    static final Map<Integer, String> FLASH_VALUES = Map.of(
            0, "No Flash",
            1, "Fired",
            5, "Fired, return not detected",
            7, "Fired, return detected",
            9, "On, fired",
            16, "Off, did not fire",
            24, "Off, did not fire, return not detected",
            25, "Auto, did not fire",
            32, "No flash function"
    );

    static final Map<Integer, String> COLOR_SPACES = Map.of(
            1, "sRGB",
            0xffff, "Uncalibrated"
    );

    static final Map<Integer, String> EXPOSURE_MODES = Map.of(
            0, "Auto exposure",
            1, "Manual exposure",
            2, "Auto bracket"
    );

    static final Map<Integer, String> WHITE_BALANCES = Map.of(
            0, "Auto white balance",
            1, "Manual white balance"
    );

    static final Map<Integer, String> SCENE_CAPTURE_TYPES = Map.of(
            0, "Standard",
            1, "Landscape",
            2, "Portrait",
            3, "Night scene"
    );
}
