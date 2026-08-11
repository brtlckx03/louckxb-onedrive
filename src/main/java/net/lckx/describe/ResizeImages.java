package net.lckx.describe;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.stream.Stream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Batch-resizes JPEGs in a directory to a smaller long-side dimension while preserving EXIF
 * (GPS, timestamps, camera info) by splicing the original APP1 segment into the resized file.
 *
 * Default is a dry run — the actual work happens only with --apply (or after the interactive
 * confirmation prompt).
 */
public class ResizeImages {

    static final Map<String, Integer> SIZE_PRESETS = Map.of(
            "a5", 2500,
            "a4", 3500,
            "a3", 5000
    );
    static final String DEFAULT_PRESET = "a5";
    static final int MIN_LONG_SIDE = 500;
    static final int MAX_LONG_SIDE = 12_000;
    static final float DEFAULT_QUALITY = 0.85f;
    static final String DEFAULT_SUBFOLDER = "resized";
    static final String DEFAULT_ORIGINALS_SUBFOLDER = "originals";

    public static void main(String[] args) {
        int exitCode = new ResizeImages().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        Scanner interactiveInput = null;
        try {
            Options options = parseOptions(args);
            boolean interactive = options.directory() == null
                    || options.longSide() == null
                    || options.outputMode() == null;
            if (interactive) {
                interactiveInput = new Scanner(System.in);
            }

            while (true) {
                if (options.directory() == null) {
                    options = options.withDirectory(promptForDirectory(interactiveInput));
                }
                if (!Files.isDirectory(options.directory())) {
                    throw new UsageException("Not a directory: " + options.directory());
                }
                if (options.longSide() == null) {
                    options = options.withLongSide(promptForLongSide(interactiveInput));
                }
                if (options.outputMode() == null) {
                    options = options.withOutputMode(promptForOutputMode(interactiveInput));
                }

                processDirectory(options, interactiveInput);

                if (!interactive) return 0;

                System.out.println();
                System.out.print("Scan another directory? Enter path (or blank to quit): ");
                System.out.flush();
                if (!interactiveInput.hasNextLine()) return 0;
                String next = unquote(interactiveInput.nextLine().trim());
                if (next.isEmpty()) return 0;
                options = options.withDirectory(expandHomePath(next));
            }
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

    private void processDirectory(Options options, Scanner interactiveInput) throws IOException {
        System.out.println("Directory:  " + options.directory().toAbsolutePath());
        System.out.println("Long side:  " + options.longSide() + " px");
        System.out.println("Quality:    " + options.quality());
        System.out.println("Output:     " + options.outputMode() + switch (options.outputMode()) {
            case SUBFOLDER -> " (" + options.subfolder() + "/)";
            case REPLACE_KEEP_ORIGINAL -> " (originals moved to " + options.originalsSubfolder() + "/)";
            case OVERWRITE -> "";
        });
        System.out.println("Recursive:  " + options.recursive());
        System.out.println("Apply:      " + options.apply());
        System.out.println();

        List<Path> jpegs = collectJpegs(options.directory(), options.recursive());
        System.out.println("Scanning " + jpegs.size() + " JPEG(s)...");

        List<Plan> plans = new ArrayList<>();
        List<Skip> skips = new ArrayList<>();

        for (Path jpeg : jpegs) {
            try {
                Dimensions dims = readJpegDimensions(jpeg);
                if (dims == null) {
                    skips.add(new Skip(jpeg, "could not decode JPEG dimensions"));
                    continue;
                }
                int longSide = Math.max(dims.width(), dims.height());
                if (longSide <= options.longSide()) {
                    skips.add(new Skip(jpeg, "already " + longSide + " px on the long side"));
                    continue;
                }
                long originalSize = Files.size(jpeg);
                double scale = (double) options.longSide() / longSide;
                int newW = Math.max(1, (int) Math.round(dims.width() * scale));
                int newH = Math.max(1, (int) Math.round(dims.height() * scale));
                Path target = resolveTarget(jpeg, options);
                plans.add(new Plan(jpeg, target, dims, new Dimensions(newW, newH), originalSize));
            } catch (IOException e) {
                skips.add(new Skip(jpeg, "read error: " + e.getMessage()));
            }
        }

        System.out.println();
        System.out.println("Planned resizes: " + plans.size());
        System.out.println("Skipped:         " + skips.size());
        if (!plans.isEmpty()) {
            System.out.println();
            System.out.println("Planned resizes:");
            for (Plan p : plans) {
                System.out.println(String.format(Locale.ROOT, "  %s  (%dx%d, %.2f MB)  →  %s  (%dx%d)",
                        p.source().getFileName(),
                        p.originalDims().width(), p.originalDims().height(),
                        p.originalBytes() / 1024.0 / 1024.0,
                        p.target().getFileName(),
                        p.newDims().width(), p.newDims().height()));
            }
        }

        boolean shouldApply = options.apply();
        if (!shouldApply && !plans.isEmpty() && interactiveInput != null) {
            System.out.println();
            System.out.print("Apply these " + plans.size() + " resize(s) now? [Y/n]: ");
            System.out.flush();
            if (interactiveInput.hasNextLine()) {
                String answer = interactiveInput.nextLine().trim().toLowerCase(Locale.ROOT);
                shouldApply = answer.isEmpty() || answer.equals("y") || answer.equals("yes");
            }
        }

        if (shouldApply) {
            int applied = applyResizes(plans, options);
            System.out.println("Resized: " + applied + " / " + plans.size());
        } else {
            System.out.println();
            System.out.println("Dry run — no files were changed.");
        }
    }

    private static Path resolveTarget(Path source, Options options) {
        return switch (options.outputMode()) {
            case OVERWRITE, REPLACE_KEEP_ORIGINAL -> source;
            case SUBFOLDER -> source.getParent().resolve(options.subfolder()).resolve(source.getFileName());
        };
    }

    private int applyResizes(List<Plan> plans, Options options) {
        int applied = 0;
        for (Plan plan : plans) {
            try {
                Path parent = plan.target().getParent();
                if (parent != null) Files.createDirectories(parent);
                byte[] original = Files.readAllBytes(plan.source());
                byte[] resized = resizeJpeg(original, plan.newDims().width(), plan.newDims().height(), options.quality());
                Path tmp = plan.target().resolveSibling(plan.target().getFileName() + ".resize.tmp");
                Files.write(tmp, resized);
                if (options.outputMode() == OutputMode.REPLACE_KEEP_ORIGINAL) {
                    Path originalsDir = plan.source().getParent().resolve(options.originalsSubfolder());
                    Files.createDirectories(originalsDir);
                    Path backup = originalsDir.resolve(plan.source().getFileName());
                    Files.move(plan.source(), backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                Files.move(tmp, plan.target(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                applied++;
                long newSize = Files.size(plan.target());
                System.out.println(String.format(Locale.ROOT, "  ✓ %s  %.2f MB → %.2f MB",
                        plan.source().getFileName(),
                        plan.originalBytes() / 1024.0 / 1024.0,
                        newSize / 1024.0 / 1024.0));
            } catch (IOException e) {
                System.err.println("  ✗ failed to resize " + plan.source().getFileName() + ": " + e.getMessage());
            }
        }
        return applied;
    }

    static byte[] resizeJpeg(byte[] original, int newWidth, int newHeight, float quality) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
        if (source == null) throw new IOException("could not decode as JPEG");
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(imageBytes)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(resized, null, null), params);
            }
        } finally {
            writer.dispose();
        }

        byte[] app1 = extractApp1Segment(original);
        byte[] baseline = imageBytes.toByteArray();
        return app1 == null ? baseline : spliceApp1AfterSoi(baseline, app1);
    }

    static byte[] extractApp1Segment(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return null;
        int pos = 2;
        while (pos + 3 < jpeg.length) {
            if ((jpeg[pos] & 0xFF) != 0xFF) return null;
            int marker = jpeg[pos + 1] & 0xFF;
            int segStart = pos;
            pos += 2;
            if (marker == 0xD9 || marker == 0xDA) return null;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;
            if (pos + 2 > jpeg.length) return null;
            int segLength = ((jpeg[pos] & 0xFF) << 8) | (jpeg[pos + 1] & 0xFF);
            int segEnd = pos + segLength;
            if (segEnd > jpeg.length) return null;
            if (marker == 0xE1 && segLength >= 8
                    && jpeg[pos + 2] == 'E' && jpeg[pos + 3] == 'x'
                    && jpeg[pos + 4] == 'i' && jpeg[pos + 5] == 'f'
                    && jpeg[pos + 6] == 0 && jpeg[pos + 7] == 0) {
                int totalLength = 2 + segLength;
                byte[] out = new byte[totalLength];
                System.arraycopy(jpeg, segStart, out, 0, totalLength);
                return out;
            }
            pos = segEnd;
        }
        return null;
    }

    static byte[] spliceApp1AfterSoi(byte[] baseline, byte[] app1) {
        if (baseline.length < 2 || (baseline[0] & 0xFF) != 0xFF || (baseline[1] & 0xFF) != 0xD8) {
            return baseline;
        }
        byte[] out = new byte[baseline.length + app1.length];
        out[0] = baseline[0];
        out[1] = baseline[1];
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(baseline, 2, out, 2 + app1.length, baseline.length - 2);
        return out;
    }

    static Dimensions readJpegDimensions(Path file) throws IOException {
        try (var stream = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(stream);
                return new Dimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    static List<Path> collectJpegs(Path directory, boolean recursive) throws IOException {
        int depth = recursive ? Integer.MAX_VALUE : 1;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory, depth)) {
            stream.filter(Files::isRegularFile)
                    .filter(ResizeImages::isJpeg)
                    .filter(p -> !inSubfolderNamed(directory, p, "resized"))
                    .filter(p -> !inSubfolderNamed(directory, p, "originals"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(files::add);
        }
        return files;
    }

    private static boolean isJpeg(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static boolean inSubfolderNamed(Path root, Path candidate, String subfolderName) {
        Path relative = root.relativize(candidate.toAbsolutePath().normalize());
        for (Path segment : relative) {
            if (segment.toString().equals(subfolderName)) return true;
        }
        return false;
    }

    static Options parseOptions(String[] args) {
        Path directory = null;
        Integer longSide = null;
        float quality = DEFAULT_QUALITY;
        OutputMode outputMode = null;
        String subfolder = DEFAULT_SUBFOLDER;
        String originalsSubfolder = DEFAULT_ORIGINALS_SUBFOLDER;
        boolean apply = false;
        boolean recursive = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                throw new HelpException();
            } else if (arg.equals("--apply")) {
                apply = true;
            } else if (arg.equals("--recursive") || arg.equals("-r")) {
                recursive = true;
            } else if (arg.equals("--size")) {
                longSide = presetLongSide(requireValue(args, ++i, "--size"));
            } else if (arg.startsWith("--size=")) {
                longSide = presetLongSide(valueAfterEquals(arg, "--size"));
            } else if (arg.equals("--long-side")) {
                longSide = parseBoundedInt(requireValue(args, ++i, "--long-side"),
                        "--long-side", MIN_LONG_SIDE, MAX_LONG_SIDE);
            } else if (arg.startsWith("--long-side=")) {
                longSide = parseBoundedInt(valueAfterEquals(arg, "--long-side"),
                        "--long-side", MIN_LONG_SIDE, MAX_LONG_SIDE);
            } else if (arg.equals("--quality")) {
                quality = parseQuality(requireValue(args, ++i, "--quality"));
            } else if (arg.startsWith("--quality=")) {
                quality = parseQuality(valueAfterEquals(arg, "--quality"));
            } else if (arg.equals("--output")) {
                outputMode = parseOutputMode(requireValue(args, ++i, "--output"));
            } else if (arg.startsWith("--output=")) {
                outputMode = parseOutputMode(valueAfterEquals(arg, "--output"));
            } else if (arg.equals("--subfolder")) {
                subfolder = requireValue(args, ++i, "--subfolder");
            } else if (arg.startsWith("--subfolder=")) {
                subfolder = valueAfterEquals(arg, "--subfolder");
            } else if (arg.equals("--originals-subfolder")) {
                originalsSubfolder = requireValue(args, ++i, "--originals-subfolder");
            } else if (arg.startsWith("--originals-subfolder=")) {
                originalsSubfolder = valueAfterEquals(arg, "--originals-subfolder");
            } else if (arg.startsWith("-")) {
                throw new UsageException("Unknown option: " + arg);
            } else if (directory == null) {
                directory = expandHomePath(arg);
            } else {
                throw new UsageException("Only one directory can be processed at a time.");
            }
        }
        return new Options(directory, longSide, quality, outputMode, subfolder, originalsSubfolder, apply, recursive);
    }

    static int presetLongSide(String preset) {
        String key = preset.trim().toLowerCase(Locale.ROOT);
        Integer value = SIZE_PRESETS.get(key);
        if (value == null) {
            throw new UsageException("Unknown --size preset '" + preset
                    + "'. Known: " + String.join(", ", SIZE_PRESETS.keySet()));
        }
        return value;
    }

    private static OutputMode parseOutputMode(String value) {
        String key = value.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "overwrite" -> OutputMode.OVERWRITE;
            case "subfolder", "copy" -> OutputMode.SUBFOLDER;
            case "replace", "backup", "replace-keep-original" -> OutputMode.REPLACE_KEEP_ORIGINAL;
            default -> throw new UsageException("--output must be 'overwrite', 'subfolder', or 'replace'");
        };
    }

    private static float parseQuality(String value) {
        try {
            float q = Float.parseFloat(value);
            if (q < 0 || q > 1) throw new UsageException("--quality must be between 0.0 and 1.0");
            return q;
        } catch (NumberFormatException e) {
            throw new UsageException("--quality must be a number.");
        }
    }

    private static Integer promptForLongSide(Scanner scanner) {
        while (true) {
            System.out.println();
            System.out.println("Target size (long side in pixels):");
            System.out.println("  1) a5   — 2500 px  (A5 print at 300 DPI, ~500KB-1MB files) [default]");
            System.out.println("  2) a4   — 3500 px  (A4 print at 300 DPI)");
            System.out.println("  3) a3   — 5000 px  (A3 print / poster)");
            System.out.println("  Or type a custom number of pixels (500 - 12000).");
            System.out.print("Choice [1]: ");
            System.out.flush();
            if (!scanner.hasNextLine()) return SIZE_PRESETS.get(DEFAULT_PRESET);
            String input = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
            if (input.isEmpty() || input.equals("1") || input.equals("a5")) return SIZE_PRESETS.get("a5");
            if (input.equals("2") || input.equals("a4")) return SIZE_PRESETS.get("a4");
            if (input.equals("3") || input.equals("a3")) return SIZE_PRESETS.get("a3");
            try {
                int px = Integer.parseInt(input);
                if (px < MIN_LONG_SIDE || px > MAX_LONG_SIDE) {
                    System.out.println("Please enter " + MIN_LONG_SIDE + " - " + MAX_LONG_SIDE + " pixels.");
                    continue;
                }
                return px;
            } catch (NumberFormatException e) {
                System.out.println("Not recognised. Try 1/2/3/a5/a4/a3 or a number.");
            }
        }
    }

    private static OutputMode promptForOutputMode(Scanner scanner) {
        while (true) {
            System.out.println();
            System.out.println("How should resized files be written?");
            System.out.println("  1) Write into a 'resized/' subfolder (keeps originals side-by-side)");
            System.out.println("  2) Overwrite the originals in place (no backup) [default]");
            System.out.println("  3) Replace files in place, move originals to 'originals/' subfolder");
            System.out.print("Choice [2]: ");
            System.out.flush();
            if (!scanner.hasNextLine()) return OutputMode.OVERWRITE;
            String input = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
            if (input.isEmpty() || input.equals("2") || input.equals("overwrite")) return OutputMode.OVERWRITE;
            if (input.equals("1") || input.equals("subfolder")) return OutputMode.SUBFOLDER;
            if (input.equals("3") || input.equals("replace")) return OutputMode.REPLACE_KEEP_ORIGINAL;
            System.out.println("Please answer 1, 2, or 3.");
        }
    }

    private static Path promptForDirectory(Scanner scanner) {
        while (true) {
            System.out.print("Directory to scan: ");
            System.out.flush();
            if (!scanner.hasNextLine()) {
                throw new UsageException("Missing directory to scan.");
            }
            String input = unquote(scanner.nextLine().trim());
            if (!input.isEmpty()) {
                return expandHomePath(input);
            }
            System.out.println("Please enter a directory path (or press Ctrl+C to cancel).");
        }
    }

    private static String unquote(String input) {
        if (input.length() >= 2
                && ((input.startsWith("\"") && input.endsWith("\""))
                    || (input.startsWith("'") && input.endsWith("'")))) {
            return input.substring(1, input.length() - 1).trim();
        }
        return input;
    }

    private static Path expandHomePath(String value) {
        if (value.equals("~")) return Path.of(System.getProperty("user.home"));
        if (value.startsWith("~/")) return Path.of(System.getProperty("user.home") + value.substring(1));
        return Path.of(value);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new UsageException("Missing value for " + option + ".");
        }
        return args[index].trim();
    }

    private static String valueAfterEquals(String arg, String option) {
        String value = arg.substring(option.length() + 1).trim();
        if (value.isBlank()) throw new UsageException("Missing value for " + option + ".");
        return value;
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
                  java -cp target/classes net.lckx.describe.ResizeImages <directory> [options]

                Options:
                  --size <preset>     Target long-side preset: a5 (2500px), a4 (3500px), a3 (5000px).
                  --long-side <px>    Explicit long-side in pixels (500 - 12000). Overrides --size.
                  --quality <0.0-1.0> JPEG output quality. Default: 0.85
                  --output <mode>     'overwrite' or 'subfolder'. Default (interactive): prompt.
                  --subfolder <name>  Subfolder name if --output=subfolder. Default: 'resized'
                  --recursive, -r     Also process subdirectories.
                  --apply             Actually perform the resizes (default is dry-run).
                  --help              Show this help.

                EXIF (GPS, DateTimeOriginal, camera info) is preserved by splicing the original
                APP1 segment into the newly encoded JPEG. Videos are left untouched.

                Example (dry run, interactive):
                  java -cp target/classes net.lckx.describe.ResizeImages

                Example (apply, a5 preset, subfolder):
                  java -cp target/classes net.lckx.describe.ResizeImages \\
                      "~/Downloads/phone-photos/20250924 Thailand" \\
                      --size a5 --output subfolder --apply
                """);
    }

    enum OutputMode { OVERWRITE, SUBFOLDER, REPLACE_KEEP_ORIGINAL }

    record Dimensions(int width, int height) {
    }

    record Plan(Path source, Path target, Dimensions originalDims, Dimensions newDims, long originalBytes) {
    }

    record Skip(Path file, String reason) {
    }

    record Options(Path directory, Integer longSide, float quality, OutputMode outputMode,
                   String subfolder, String originalsSubfolder, boolean apply, boolean recursive) {
        Options withDirectory(Path newDirectory) {
            return new Options(newDirectory, longSide, quality, outputMode, subfolder, originalsSubfolder, apply, recursive);
        }
        Options withLongSide(Integer newLongSide) {
            return new Options(directory, newLongSide, quality, outputMode, subfolder, originalsSubfolder, apply, recursive);
        }
        Options withOutputMode(OutputMode newMode) {
            return new Options(directory, longSide, quality, newMode, subfolder, originalsSubfolder, apply, recursive);
        }
    }

    static class UsageException extends RuntimeException {
        UsageException(String message) { super(message); }
    }

    static class HelpException extends UsageException {
        HelpException() { super("ResizeImages help"); }
    }
}
