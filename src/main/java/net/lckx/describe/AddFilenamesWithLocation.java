package net.lckx.describe;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.stream.Stream;

/**
 * Batch-renames photos and videos in a directory by appending location info to each filename.
 *
 * Default is a dry run: writes a rename-plan report next to the directory (or wherever
 * --report points) and does NOT touch any files. Rerun with --apply to actually rename.
 */
public class AddFilenamesWithLocation {

    private static final String DEFAULT_USER_AGENT = "net.lckx.addfilenameswithlocation/1.0 (personal CLI)";
    private static final int DEFAULT_NOMINATIM_TIMEOUT_SECONDS = 15;
    private static final Duration DEFAULT_TIMELINE_MAX_GAP = Duration.ofMinutes(30);
    private static final Duration DEFAULT_NOMINATIM_MIN_INTERVAL = Duration.ofMillis(1100);
    private static final String DEFAULT_REPORT_NAME = "rename-plan.txt";

    public static void main(String[] args) {
        int exitCode = new AddFilenamesWithLocation().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        Scanner interactiveInput = null;
        try {
            Options options = parseOptions(args);
            boolean interactive = options.directory() == null;
            if (interactive) {
                interactiveInput = new Scanner(System.in);
            }

            while (true) {
                if (options.directory() == null) {
                    if (interactiveInput == null) interactiveInput = new Scanner(System.in);
                    options = options.withDirectory(promptForDirectory(interactiveInput));
                }
                if (!Files.isDirectory(options.directory())) {
                    throw new UsageException("Not a directory: " + options.directory());
                }
                if (options.timelinePath() == null) {
                    if (interactiveInput == null) interactiveInput = new Scanner(System.in);
                    options = options.withTimelinePath(promptForTimeline(interactiveInput));
                }
                if (options.timelinePath() == null || !Files.isRegularFile(options.timelinePath())) {
                    throw new UsageException("Timeline JSON not found. Pass --timeline <path> "
                            + "or place it somewhere auto-detectable (e.g. src/main/resources/Tijdlijn.json).");
                }

                processDirectory(options, interactiveInput);

                if (!interactive) return 0;

                System.out.println();
                System.out.print("Scan another directory? Enter path (or blank to quit): ");
                System.out.flush();
                if (!interactiveInput.hasNextLine()) return 0;
                String next = unquote(interactiveInput.nextLine().trim());
                if (next.isEmpty()) return 0;
                Path nextDirectory = expandHomePath(next);
                options = options.withDirectoryAndDefaultReport(nextDirectory);
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
        System.out.println("Timeline:   " + options.timelinePath().toAbsolutePath());
        System.out.println("Recursive:  " + options.recursive());
        System.out.println("Apply:      " + options.apply());
        System.out.println();

        List<Path> mediaFiles = collectMediaFiles(options.directory(), options.recursive());
        System.out.println("Scanning " + mediaFiles.size() + " media file(s)...");

        PrintMediaLocation.TimelineIndex timeline = PrintMediaLocation.TimelineIndex.load(options.timelinePath());
        PrintMediaLocation geocoder = new PrintMediaLocation();

        List<RenamePlan> renames = new ArrayList<>();
        List<SkipReason> skips = new ArrayList<>();
        Map<String, List<String>> nominatimCache = new HashMap<>();
        long lastNominatimCallNanos = 0L;

        for (Path file : mediaFiles) {
            Optional<PrintMediaLocation.GpsFix> gps = extractGps(file, timeline, options);
            if (gps.isEmpty()) {
                skips.add(new SkipReason(file, "no GPS available (no EXIF/atom, no Timeline match)"));
                continue;
            }

            String cacheKey = String.format(Locale.ROOT, "%.4f,%.4f",
                    gps.get().latitude(), gps.get().longitude());
            List<String> parts = nominatimCache.get(cacheKey);
            if (parts == null) {
                long minIntervalNanos = options.nominatimMinInterval().toNanos();
                long waitNanos = minIntervalNanos - (System.nanoTime() - lastNominatimCallNanos);
                if (waitNanos > 0) {
                    Thread.sleep(Duration.ofNanos(waitNanos).toMillis() + 1);
                }
                Optional<List<String>> reply = geocoder.reverseGeocodeParts(
                        gps.get(),
                        options.userAgent(),
                        options.nominatimTimeout(),
                        options.acceptLanguage());
                lastNominatimCallNanos = System.nanoTime();
                parts = reply.orElse(List.of());
                nominatimCache.put(cacheKey, parts);
                System.out.println("  ↳ geocoded " + file.getFileName() + " → "
                        + (parts.isEmpty() ? "(nothing)" : String.join(", ", parts)));
            }

            if (parts.isEmpty()) {
                skips.add(new SkipReason(file, "no address returned by Nominatim"));
                continue;
            }

            String suggested = PrintMediaLocation.suggestFilename(file, parts);
            if (suggested.equals(file.getFileName().toString())) {
                skips.add(new SkipReason(file, "current filename already matches suggestion"));
                continue;
            }
            Path target = uniqueTarget(file.resolveSibling(suggested), file);
            renames.add(new RenamePlan(file, target));
        }

        writeReport(options.reportPath(), options, mediaFiles.size(), renames, skips);

        System.out.println();
        System.out.println("Planned renames: " + renames.size());
        System.out.println("Skipped:         " + skips.size());
        System.out.println("Report written:  " + options.reportPath().toAbsolutePath());
        if (!renames.isEmpty()) {
            System.out.println();
            System.out.println("Planned renames:");
            for (RenamePlan plan : renames) {
                System.out.println("  " + plan.from().getFileName() + "  →  " + plan.to().getFileName());
            }
        }

        boolean shouldApply = options.apply();
        if (!shouldApply && !renames.isEmpty() && interactiveInput != null) {
            System.out.println();
            System.out.print("Apply these " + renames.size() + " rename(s) now? [Y/n]: ");
            System.out.flush();
            if (interactiveInput.hasNextLine()) {
                String answer = interactiveInput.nextLine().trim().toLowerCase(Locale.ROOT);
                shouldApply = answer.isEmpty() || answer.equals("y") || answer.equals("yes");
            }
        }

        if (shouldApply) {
            int applied = applyRenames(renames);
            System.out.println("Applied renames: " + applied + " / " + renames.size());
            if (applied == renames.size() && !renames.isEmpty()) {
                try {
                    Files.deleteIfExists(options.reportPath());
                    System.out.println("Report removed: " + options.reportPath().toAbsolutePath());
                } catch (IOException e) {
                    System.err.println("Warning: could not delete report " + options.reportPath() + ": " + e.getMessage());
                }
            }
        } else {
            System.out.println();
            System.out.println("Dry run — no files were changed.");
            System.out.println("Rerun with --apply to perform the renames.");
        }
    }

    private Optional<PrintMediaLocation.GpsFix> extractGps(Path file,
                                                          PrintMediaLocation.TimelineIndex timeline,
                                                          Options options) throws IOException {
        Optional<PrintMediaLocation.GpsFix> embedded = PrintMediaLocation.readGpsFromMedia(file);
        if (embedded.isPresent()) return embedded;

        Optional<Instant> instant = PrintMediaLocation.readMediaInstant(file, options.photoZone());
        if (instant.isEmpty()) return Optional.empty();

        Optional<PrintMediaLocation.TimelineMatch> match = timeline.nearest(instant.get(), options.timelineMaxGap());
        return match.map(m -> new PrintMediaLocation.GpsFix(
                m.point().latitude(), m.point().longitude()));
    }

    private static Path uniqueTarget(Path desired, Path original) {
        if (!Files.exists(desired) || desired.equals(original)) return desired;
        String name = desired.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int counter = 2; counter < 1000; counter++) {
            Path candidate = desired.resolveSibling(stem + "-" + counter + ext);
            if (!Files.exists(candidate) || candidate.equals(original)) {
                return candidate;
            }
        }
        return desired;
    }

    static List<Path> collectMediaFiles(Path directory, boolean recursive) throws IOException {
        int depth = recursive ? Integer.MAX_VALUE : 1;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory, depth)) {
            stream.filter(Files::isRegularFile)
                    .filter(AddFilenamesWithLocation::isSupportedMedia)
                    .sorted(Comparator.naturalOrder())
                    .forEach(files::add);
        }
        return files;
    }

    private static boolean isSupportedMedia(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4v");
    }

    private static void writeReport(Path reportPath, Options options, int scanned,
                                    List<RenamePlan> renames, List<SkipReason> skips) throws IOException {
        Path parent = reportPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter w = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
            w.write("# Rename plan\n");
            w.write("Directory:      " + options.directory().toAbsolutePath() + "\n");
            w.write("Timeline:       " + options.timelinePath().toAbsolutePath() + "\n");
            w.write("Recursive:      " + options.recursive() + "\n");
            w.write("Files scanned:  " + scanned + "\n");
            w.write("Renames planned:" + renames.size() + "\n");
            w.write("Skipped:        " + skips.size() + "\n");
            w.write("\n## Renames\n");
            for (RenamePlan plan : renames) {
                w.write(plan.from().getFileName() + "  →  " + plan.to().getFileName() + "\n");
            }
            w.write("\n## Skipped\n");
            for (SkipReason skip : skips) {
                w.write(skip.file().getFileName() + "  (" + skip.reason() + ")\n");
            }
        }
    }

    private int applyRenames(List<RenamePlan> renames) throws IOException {
        int applied = 0;
        for (RenamePlan plan : renames) {
            try {
                Files.move(plan.from(), plan.to(), StandardCopyOption.ATOMIC_MOVE);
                applied++;
            } catch (IOException e) {
                System.err.println("  Failed to rename " + plan.from().getFileName()
                        + " → " + plan.to().getFileName() + ": " + e.getMessage());
            }
        }
        return applied;
    }

    static Options parseOptions(String[] args) {
        Path directory = null;
        Path timelinePath = null;
        Duration timelineMaxGap = DEFAULT_TIMELINE_MAX_GAP;
        ZoneId photoZone = ZoneId.systemDefault();
        String acceptLanguage = PrintMediaLocation.defaultAcceptLanguage();
        String userAgent = DEFAULT_USER_AGENT;
        Duration nominatimTimeout = Duration.ofSeconds(DEFAULT_NOMINATIM_TIMEOUT_SECONDS);
        Duration nominatimMinInterval = DEFAULT_NOMINATIM_MIN_INTERVAL;
        Path reportPath = null;
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
            } else if (arg.equals("--timeline")) {
                timelinePath = expandHomePath(requireValue(args, ++i, "--timeline"));
            } else if (arg.startsWith("--timeline=")) {
                timelinePath = expandHomePath(valueAfterEquals(arg, "--timeline"));
            } else if (arg.equals("--timeline-max-gap-minutes")) {
                timelineMaxGap = Duration.ofMinutes(parseBoundedInt(
                        requireValue(args, ++i, "--timeline-max-gap-minutes"),
                        "--timeline-max-gap-minutes", 1, 1440));
            } else if (arg.startsWith("--timeline-max-gap-minutes=")) {
                timelineMaxGap = Duration.ofMinutes(parseBoundedInt(
                        valueAfterEquals(arg, "--timeline-max-gap-minutes"),
                        "--timeline-max-gap-minutes", 1, 1440));
            } else if (arg.equals("--photo-zone")) {
                photoZone = ZoneId.of(requireValue(args, ++i, "--photo-zone"));
            } else if (arg.startsWith("--photo-zone=")) {
                photoZone = ZoneId.of(valueAfterEquals(arg, "--photo-zone"));
            } else if (arg.equals("--language")) {
                acceptLanguage = requireValue(args, ++i, "--language");
            } else if (arg.startsWith("--language=")) {
                acceptLanguage = valueAfterEquals(arg, "--language");
            } else if (arg.equals("--report")) {
                reportPath = expandHomePath(requireValue(args, ++i, "--report"));
            } else if (arg.startsWith("--report=")) {
                reportPath = expandHomePath(valueAfterEquals(arg, "--report"));
            } else if (arg.equals("--nominatim-interval-ms")) {
                nominatimMinInterval = Duration.ofMillis(parseBoundedInt(
                        requireValue(args, ++i, "--nominatim-interval-ms"),
                        "--nominatim-interval-ms", 0, 60_000));
            } else if (arg.startsWith("--nominatim-interval-ms=")) {
                nominatimMinInterval = Duration.ofMillis(parseBoundedInt(
                        valueAfterEquals(arg, "--nominatim-interval-ms"),
                        "--nominatim-interval-ms", 0, 60_000));
            } else if (arg.startsWith("-")) {
                throw new UsageException("Unknown option: " + arg);
            } else if (directory == null) {
                directory = expandHomePath(arg);
            } else {
                throw new UsageException("Only one directory can be processed at a time.");
            }
        }

        if (timelinePath == null) {
            timelinePath = PrintMediaLocation.autoDetectTimeline();
        }
        if (reportPath == null && directory != null) {
            reportPath = directory.resolve(DEFAULT_REPORT_NAME);
        }
        return new Options(directory, timelinePath, timelineMaxGap, photoZone, acceptLanguage,
                userAgent, nominatimTimeout, nominatimMinInterval, reportPath, apply, recursive);
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java --enable-preview src/main/java/net/lckx/describe/AddFilenamesWithLocation.java <directory> [options]

                Options:
                  --apply                         Actually rename files. Without this flag it is a dry run.
                  --recursive, -r                 Also process files inside subdirectories.
                  --timeline <path>               Google Timeline JSON. Auto-detected if omitted.
                  --timeline-max-gap-minutes <n>  Reject Timeline points further from the photo's
                                                  timestamp than this. Default: 30, max: 1440.
                  --photo-zone <zone-id>          Timezone assumed when photo timestamp has no offset.
                                                  Default: system timezone.
                  --language <accept-language>    Nominatim place-name language, e.g. "en" or "nl,en;q=0.5".
                  --report <path>                 Where to write the plan/report. Default: <directory>/rename-plan.txt
                  --nominatim-interval-ms <n>     Minimum ms between Nominatim calls (rate-limit friendliness).
                                                  Default: 1100.
                  --help                          Show this help.

                Nominatim policy asks for max 1 request/second and a real User-Agent — the default
                interval of 1100ms and the descriptive User-Agent already satisfy that.

                Example (dry run):
                  java -cp target/classes net.lckx.describe.AddFilenamesWithLocation "~/Downloads/phone-photos/20250924 Thailand"

                Example (apply):
                  java -cp target/classes net.lckx.describe.AddFilenamesWithLocation \\
                      "~/Downloads/phone-photos/20250924 Thailand" --apply
                """);
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

    private static Path promptForTimeline(Scanner scanner) {
        System.out.print("Timeline JSON path (leave blank to skip): ");
        System.out.flush();
        if (!scanner.hasNextLine()) return null;
        String input = unquote(scanner.nextLine().trim());
        return input.isEmpty() ? null : expandHomePath(input);
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

    record Options(Path directory, Path timelinePath, Duration timelineMaxGap, ZoneId photoZone,
                   String acceptLanguage, String userAgent, Duration nominatimTimeout,
                   Duration nominatimMinInterval, Path reportPath, boolean apply, boolean recursive) {
        Options withDirectory(Path newDirectory) {
            Path newReportPath = reportPath;
            if (newReportPath == null && newDirectory != null) {
                newReportPath = newDirectory.resolve(DEFAULT_REPORT_NAME);
            }
            return new Options(newDirectory, timelinePath, timelineMaxGap, photoZone,
                    acceptLanguage, userAgent, nominatimTimeout, nominatimMinInterval,
                    newReportPath, apply, recursive);
        }

        Options withTimelinePath(Path newTimelinePath) {
            return new Options(directory, newTimelinePath, timelineMaxGap, photoZone,
                    acceptLanguage, userAgent, nominatimTimeout, nominatimMinInterval,
                    reportPath, apply, recursive);
        }

        Options withDirectoryAndDefaultReport(Path newDirectory) {
            Path newReportPath = newDirectory == null ? null : newDirectory.resolve(DEFAULT_REPORT_NAME);
            return new Options(newDirectory, timelinePath, timelineMaxGap, photoZone,
                    acceptLanguage, userAgent, nominatimTimeout, nominatimMinInterval,
                    newReportPath, apply, recursive);
        }
    }

    record RenamePlan(Path from, Path to) {
    }

    record SkipReason(Path file, String reason) {
    }

    static class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    static class HelpException extends UsageException {
        HelpException() {
            super("AddFilenamesWithLocation help");
        }
    }
}
