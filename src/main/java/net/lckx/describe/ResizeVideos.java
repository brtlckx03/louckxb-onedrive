package net.lckx.describe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static net.lckx.describe.ResizeImages.OutputMode.REPLACE_KEEP_ORIGINAL;

/**
 * Batch-resizes videos in a directory to a smaller resolution using ffmpeg. Preserves
 * container metadata (including the ©xyz GPS location atom and creation_time) via
 * -map_metadata 0 -movflags use_metadata_tags+faststart. Default is a dry run.
 */
public class ResizeVideos {

    static final Map<String, Integer> SIZE_PRESETS = Map.of(
            "480p", 854,
            "720p", 1280,
            "1080p", 1920
    );
    static final String DEFAULT_PRESET = "720p";
    static final int MIN_LONG_SIDE = 320;
    static final int MAX_LONG_SIDE = 8_000;
    static final int DEFAULT_CRF = 23;
    static final int MIN_CRF = 15;
    static final int MAX_CRF = 32;
    static final String DEFAULT_SUBFOLDER = "resized";
    static final String DEFAULT_ORIGINALS_SUBFOLDER = "originals";

    public static void main(String[] args) {
        int exitCode = new ResizeVideos().run(args);
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
            if (interactive) interactiveInput = new Scanner(System.in);

            checkFfmpegAvailable(options.ffmpeg(), options.ffprobe());

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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted.");
            return 130;
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private void processDirectory(Options options, Scanner interactiveInput) throws IOException, InterruptedException {
        System.out.println("Directory:  " + options.directory().toAbsolutePath());
        System.out.println("Long side:  " + options.longSide() + " px");
        System.out.println("CRF:        " + options.crf() + " (lower = better quality)");
        System.out.println("Encoder:    " + (options.hardwareAcceleration() ? "h264_videotoolbox (hardware)" : "libx264 (software)"));
        System.out.println("Output:     " + options.outputMode() + switch (options.outputMode()) {
            case SUBFOLDER -> " (" + options.subfolder() + "/)";
            case REPLACE_KEEP_ORIGINAL -> " (originals moved to " + options.originalsSubfolder() + "/)";
            case OVERWRITE -> "";
        });
        System.out.println("Recursive:  " + options.recursive());
        System.out.println("Apply:      " + options.apply());
        System.out.println();

        List<Path> videos = collectVideos(options.directory(), options.recursive(), options.subfolder());
        System.out.println("Scanning " + videos.size() + " video(s)...");

        List<Plan> plans = new ArrayList<>();
        List<Skip> skips = new ArrayList<>();

        for (Path video : videos) {
            try {
                Dim dims = probeVideoDimensions(options.ffprobe(), video);
                if (dims == null) {
                    skips.add(new Skip(video, "could not read video dimensions"));
                    continue;
                }
                int longSide = Math.max(dims.width(), dims.height());
                if (longSide <= options.longSide()) {
                    skips.add(new Skip(video, "already " + dims.width() + "x" + dims.height()
                            + ", long side " + longSide + " ≤ target " + options.longSide()));
                    continue;
                }
                long originalSize = Files.size(video);
                double scale = (double) options.longSide() / longSide;
                int newW = Math.max(2, roundEven((int) Math.round(dims.width() * scale)));
                int newH = Math.max(2, roundEven((int) Math.round(dims.height() * scale)));
                Path target = resolveTarget(video, options);
                plans.add(new Plan(video, target, dims, new Dim(newW, newH), originalSize));
            } catch (IOException e) {
                skips.add(new Skip(video, "probe error: " + e.getMessage()));
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
        if (!skips.isEmpty()) {
            System.out.println();
            System.out.println("Skipped:");
            for (Skip s : skips) {
                System.out.println("  " + s.file().getFileName() + "  (" + s.reason() + ")");
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

    private int applyResizes(List<Plan> plans, Options options) throws IOException, InterruptedException {
        int applied = 0;
        for (Plan plan : plans) {
            Path parent = plan.target().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = plan.target().resolveSibling(plan.target().getFileName() + ".resize.tmp.mp4");
            long start = System.nanoTime();
            System.out.print("  " + plan.source().getFileName() + " ... ");
            System.out.flush();
            try {
                runFfmpeg(plan.source(), tmp, plan.newDims(), options);
                if (options.outputMode() == OutputMode.REPLACE_KEEP_ORIGINAL) {
                    Path originalsDir = plan.source().getParent().resolve(options.originalsSubfolder());
                    Files.createDirectories(originalsDir);
                    Path backup = originalsDir.resolve(plan.source().getFileName());
                    Files.move(plan.source(), backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                Files.move(tmp, plan.target(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                long newSize = Files.size(plan.target());
                double seconds = (System.nanoTime() - start) / 1e9;
                System.out.println(String.format(Locale.ROOT, "%.2f MB → %.2f MB  (%.1fs)",
                        plan.originalBytes() / 1024.0 / 1024.0,
                        newSize / 1024.0 / 1024.0,
                        seconds));
                applied++;
            } catch (IOException e) {
                System.out.println("failed: " + e.getMessage());
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        }
        return applied;
    }

    private static void runFfmpeg(Path source, Path target, Dim newDims, Options options)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(options.ffmpeg());
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(source.toString());
        cmd.add("-vf");
        cmd.add("scale=" + newDims.width() + ":" + newDims.height());
        if (options.hardwareAcceleration()) {
            cmd.add("-c:v");
            cmd.add("h264_videotoolbox");
            cmd.add("-b:v");
            cmd.add(estimateBitrateKbps(newDims) + "k");
        } else {
            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-crf");
            cmd.add(String.valueOf(options.crf()));
            cmd.add("-preset");
            cmd.add("medium");
        }
        cmd.add("-c:a");
        cmd.add("copy");
        cmd.add("-map_metadata");
        cmd.add("0");
        cmd.add("-movflags");
        cmd.add("use_metadata_tags+faststart");
        cmd.add(target.toString());

        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("ffmpeg did not finish within 30 minutes");
        }
        if (process.exitValue() != 0) {
            String msg = output.toString().strip();
            throw new IOException("ffmpeg exit code " + process.exitValue()
                    + (msg.isEmpty() ? "" : ": " + msg.substring(0, Math.min(300, msg.length()))));
        }
    }

    private static int estimateBitrateKbps(Dim dims) {
        long pixels = (long) dims.width() * dims.height();
        return (int) Math.max(500, pixels / 1200);
    }

    private static int roundEven(int value) {
        return value - (value % 2);
    }

    static Dim probeVideoDimensions(String ffprobe, Path video) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                ffprobe, "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height:stream_tags=rotate:stream_side_data=rotation",
                "-of", "default=nk=1:nw=1",
                video.toString()
        ).redirectErrorStream(true).start();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) lines.add(line.trim());
            }
        }
        process.waitFor(30, TimeUnit.SECONDS);
        if (process.exitValue() != 0 || lines.size() < 2) {
            return null;
        }
        try {
            int rawW = Integer.parseInt(lines.get(0));
            int rawH = Integer.parseInt(lines.get(1));
            int rotation = 0;
            for (int i = 2; i < lines.size(); i++) {
                try {
                    rotation = Integer.parseInt(lines.get(i));
                    break;
                } catch (NumberFormatException ignored) {
                    // side_data prints "rotation" as a key/value block on some ffprobe versions
                }
            }
            int normalized = ((rotation % 360) + 360) % 360;
            if (normalized == 90 || normalized == 270) {
                return new Dim(rawH, rawW);
            }
            return new Dim(rawW, rawH);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void checkFfmpegAvailable(String ffmpeg, String ffprobe) {
        try {
            Process process = new ProcessBuilder(ffmpeg, "-version").redirectErrorStream(true).start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new UsageException("Could not run " + ffmpeg + ". Install ffmpeg (brew install ffmpeg) or pass --ffmpeg <path>.");
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new UsageException("Could not run " + ffmpeg + ": " + e.getMessage()
                    + ". Install ffmpeg (brew install ffmpeg) or pass --ffmpeg <path>.");
        }
        try {
            Process process = new ProcessBuilder(ffprobe, "-version").redirectErrorStream(true).start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new UsageException("Could not run " + ffprobe + ". ffprobe usually ships with ffmpeg.");
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new UsageException("Could not run " + ffprobe + ": " + e.getMessage() + ".");
        }
    }

    static List<Path> collectVideos(Path directory, boolean recursive, String subfolderName) throws IOException {
        int depth = recursive ? Integer.MAX_VALUE : 1;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory, depth)) {
            stream.filter(Files::isRegularFile)
                    .filter(ResizeVideos::isVideo)
                    .filter(p -> !inSubfolderNamed(directory, p, subfolderName))
                    .filter(p -> !inSubfolderNamed(directory, p, "originals"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(files::add);
        }
        return files;
    }

    private static boolean isVideo(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4v");
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
        int crf = DEFAULT_CRF;
        OutputMode outputMode = null;
        String subfolder = DEFAULT_SUBFOLDER;
        String originalsSubfolder = DEFAULT_ORIGINALS_SUBFOLDER;
        boolean apply = false;
        boolean recursive = false;
        boolean hardwareAcceleration = false;
        String ffmpeg = "ffmpeg";
        String ffprobe = "ffprobe";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                throw new HelpException();
            } else if (arg.equals("--apply")) {
                apply = true;
            } else if (arg.equals("--recursive") || arg.equals("-r")) {
                recursive = true;
            } else if (arg.equals("--fast")) {
                hardwareAcceleration = true;
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
            } else if (arg.equals("--crf")) {
                crf = parseBoundedInt(requireValue(args, ++i, "--crf"), "--crf", MIN_CRF, MAX_CRF);
            } else if (arg.startsWith("--crf=")) {
                crf = parseBoundedInt(valueAfterEquals(arg, "--crf"), "--crf", MIN_CRF, MAX_CRF);
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
            } else if (arg.equals("--ffmpeg")) {
                ffmpeg = requireValue(args, ++i, "--ffmpeg");
            } else if (arg.startsWith("--ffmpeg=")) {
                ffmpeg = valueAfterEquals(arg, "--ffmpeg");
            } else if (arg.equals("--ffprobe")) {
                ffprobe = requireValue(args, ++i, "--ffprobe");
            } else if (arg.startsWith("--ffprobe=")) {
                ffprobe = valueAfterEquals(arg, "--ffprobe");
            } else if (arg.startsWith("-")) {
                throw new UsageException("Unknown option: " + arg);
            } else if (directory == null) {
                directory = expandHomePath(arg);
            } else {
                throw new UsageException("Only one directory can be processed at a time.");
            }
        }
        return new Options(directory, longSide, crf, outputMode, subfolder, originalsSubfolder,
                apply, recursive, hardwareAcceleration, ffmpeg, ffprobe);
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

    private static Integer promptForLongSide(Scanner scanner) {
        while (true) {
            System.out.println();
            System.out.println("Target video size (long-side pixels):");
            System.out.println("  1) 720p  — 1280 long side (default, good for laptop/TV screens)");
            System.out.println("  2) 480p  — 854  long side (smallest, mobile-friendly)");
            System.out.println("  3) 1080p — 1920 long side (Full HD, larger files)");
            System.out.println("  Or type a custom number of pixels (" + MIN_LONG_SIDE + " - " + MAX_LONG_SIDE + ").");
            System.out.print("Choice [1]: ");
            System.out.flush();
            if (!scanner.hasNextLine()) return SIZE_PRESETS.get(DEFAULT_PRESET);
            String input = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
            if (input.isEmpty() || input.equals("1") || input.equals("720p")) return SIZE_PRESETS.get("720p");
            if (input.equals("2") || input.equals("480p")) return SIZE_PRESETS.get("480p");
            if (input.equals("3") || input.equals("1080p")) return SIZE_PRESETS.get("1080p");
            try {
                int px = Integer.parseInt(input);
                if (px < MIN_LONG_SIDE || px > MAX_LONG_SIDE) {
                    System.out.println("Please enter " + MIN_LONG_SIDE + " - " + MAX_LONG_SIDE + " pixels.");
                    continue;
                }
                return px;
            } catch (NumberFormatException e) {
                System.out.println("Not recognised. Try 1/2/3/720p/480p/1080p or a number.");
            }
        }
    }

    private static OutputMode promptForOutputMode(Scanner scanner) {
        while (true) {
            System.out.println();
            System.out.println("How should resized videos be written?");
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
            if (!scanner.hasNextLine()) throw new UsageException("Missing directory to scan.");
            String input = unquote(scanner.nextLine().trim());
            if (!input.isEmpty()) return expandHomePath(input);
            System.out.println("Please enter a directory path (or press Ctrl+C to cancel).");
        }
    }

    private static String unquote(String input) {
        if (input.length() >= 2 && ((input.startsWith("\"") && input.endsWith("\""))
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
                  java -cp target/classes net.lckx.describe.ResizeVideos <directory> [options]

                Options:
                  --size <preset>     Target long-side preset: 480p (854px), 720p (1280px), 1080p (1920px).
                  --long-side <px>    Explicit long-side in pixels (320 - 8000). Overrides --size.
                  --crf <15-32>       H.264 quality (lower = better). Default: 23.
                  --output <mode>     'overwrite' or 'subfolder'. Default (interactive): prompt.
                  --subfolder <name>  Subfolder name if --output=subfolder. Default: 'resized'
                  --recursive, -r     Also process subdirectories.
                  --apply             Actually perform the resizes (default is dry-run).
                  --fast              Use hardware-accelerated encoder (h264_videotoolbox on macOS).
                                      Much faster; slightly lower quality per bitrate.
                  --ffmpeg <path>     Path to ffmpeg binary. Default: 'ffmpeg' on PATH.
                  --ffprobe <path>    Path to ffprobe. Default: 'ffprobe' on PATH.
                  --help              Show this help.

                Requires ffmpeg + ffprobe (brew install ffmpeg). Container metadata including the
                mvhd creation_time and the ©xyz GPS atom is preserved via
                -map_metadata 0 -movflags use_metadata_tags+faststart. Audio is copied as-is.
                """);
    }

    enum OutputMode { OVERWRITE, SUBFOLDER, REPLACE_KEEP_ORIGINAL }

    record Dim(int width, int height) {
    }

    record Plan(Path source, Path target, Dim originalDims, Dim newDims, long originalBytes) {
    }

    record Skip(Path file, String reason) {
    }

    record Options(Path directory, Integer longSide, int crf, OutputMode outputMode,
                   String subfolder, String originalsSubfolder, boolean apply, boolean recursive,
                   boolean hardwareAcceleration, String ffmpeg, String ffprobe) {
        Options withDirectory(Path newDirectory) {
            return new Options(newDirectory, longSide, crf, outputMode, subfolder, originalsSubfolder,
                    apply, recursive, hardwareAcceleration, ffmpeg, ffprobe);
        }
        Options withLongSide(Integer newLongSide) {
            return new Options(directory, newLongSide, crf, outputMode, subfolder, originalsSubfolder,
                    apply, recursive, hardwareAcceleration, ffmpeg, ffprobe);
        }
        Options withOutputMode(OutputMode newMode) {
            return new Options(directory, longSide, crf, newMode, subfolder, originalsSubfolder,
                    apply, recursive, hardwareAcceleration, ffmpeg, ffprobe);
        }
    }

    static class UsageException extends RuntimeException {
        UsageException(String message) { super(message); }
    }

    static class HelpException extends UsageException {
        HelpException() { super("ResizeVideos help"); }
    }
}
