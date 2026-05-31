package net.lckx.video;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Reviews generated video-people candidate images and either renames them to a known person or deletes them.
 */
public class ReviewPersonCandidates {
    private static final Path DEFAULT_PEOPLE_DIR = Path.of(System.getProperty("user.dir"), "video-people").toAbsolutePath().normalize();
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "bmp", "gif");
    private static final int DEFAULT_TERMINAL_WIDTH = 80;
    private static final int MIN_TERMINAL_WIDTH = 20;
    private static final int MAX_TERMINAL_WIDTH = 200;
    private static final String ASCII_RAMP = "@%#*+=-:. ";

    public static void main(String[] args) {
        int exitCode = new ReviewPersonCandidates().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        try {
            Options options = parseOptions(args);
            if (options.help()) {
                printHelp();
                return 0;
            }
            if (!Files.isDirectory(options.peopleDir())) {
                throw new UsageException("People directory does not exist: " + options.peopleDir());
            }

            List<Path> candidates = findCandidateImages(options.peopleDir());
            if (candidates.isEmpty()) {
                System.out.println("No remaining frame candidate images found in: " + options.peopleDir());
                return 0;
            }

            System.out.println("Reviewing " + candidates.size() + " frame candidate image(s) in: " + options.peopleDir());
            System.out.println("Type a person name to rename, d to delete, s to skip, or q to quit.");
            if (options.viewer() == Viewer.TERMINAL || options.viewer() == Viewer.BOTH) {
                System.out.println("Terminal preview is ASCII-only; use --viewer open or --viewer both for the real image.");
            }
            System.out.println();

            Scanner scanner = new Scanner(System.in);
            int renamed = 0;
            int deleted = 0;
            int skipped = 0;

            for (int i = 0; i < candidates.size(); i++) {
                Path candidate = candidates.get(i);
                System.out.println("[" + (i + 1) + "/" + candidates.size() + "] " + options.peopleDir().relativize(candidate));
                showCandidate(candidate, options);

                ReviewAction action = askAction(scanner, candidate);
                if (action == ReviewAction.QUIT) {
                    break;
                }
                if (action == ReviewAction.SKIP) {
                    skipped++;
                    System.out.println();
                    continue;
                }
                if (action == ReviewAction.DELETED) {
                    deleted++;
                    System.out.println();
                    continue;
                }
                renamed++;
                System.out.println();
            }

            System.out.println("Done. Renamed: " + renamed + ", deleted: " + deleted + ", skipped: " + skipped + ".");
            return 0;
        } catch (UsageException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Run with --help for usage.");
            return 2;
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static void showCandidate(Path candidate, Options options) {
        if (options.viewer() == Viewer.TERMINAL || options.viewer() == Viewer.BOTH) {
            printAsciiPreview(candidate, options.terminalWidth());
        }
        if (options.viewer() == Viewer.OPEN || options.viewer() == Viewer.BOTH) {
            openWithSystemViewer(candidate);
        }
    }

    private static ReviewAction askAction(Scanner scanner, Path candidate) throws IOException {
        while (true) {
            System.out.print("Person name or target filename ([d]elete, [s]kip, [q]uit): ");
            if (!scanner.hasNextLine()) {
                return ReviewAction.QUIT;
            }

            String input = scanner.nextLine().trim();
            if (input.isBlank()) {
                System.out.println("Please enter a person name, d, s, or q.");
                continue;
            }

            String command = input.toLowerCase(Locale.ROOT);
            if (command.equals("q") || command.equals("quit")) {
                return ReviewAction.QUIT;
            }
            if (command.equals("s") || command.equals("skip")) {
                return ReviewAction.SKIP;
            }
            if (command.equals("d") || command.equals("delete")) {
                if (confirm(scanner, "Delete this image? [y/N] ")) {
                    Files.delete(candidate);
                    System.out.println("Deleted: " + candidate.getFileName());
                    return ReviewAction.DELETED;
                }
                continue;
            }

            Path target = buildRenameTarget(candidate, input);
            if (Files.exists(target)) {
                System.out.println("Target already exists: " + target.getFileName());
                continue;
            }

            Files.move(candidate, target);
            System.out.println("Renamed to: " + target.getFileName());
            return ReviewAction.RENAMED;
        }
    }

    private static boolean confirm(Scanner scanner, String prompt) {
        System.out.print(prompt);
        if (!scanner.hasNextLine()) {
            return false;
        }
        String answer = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
        return answer.equals("y") || answer.equals("yes");
    }

    static List<Path> findCandidateImages(Path peopleDir) throws IOException {
        try (Stream<Path> paths = Files.walk(peopleDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(ReviewPersonCandidates::isCandidateImage)
                    .sorted()
                    .toList();
        }
    }

    static boolean isCandidateImage(Path path) {
        String filename = path.getFileName().toString();
        return filename.toLowerCase(Locale.ROOT).contains("frame") && IMAGE_EXTENSIONS.contains(extensionWithoutDot(filename));
    }

    static Path buildRenameTarget(Path source, String requestedName) {
        String trimmed = requestedName.trim();
        if (trimmed.isBlank()) {
            throw new UsageException("Person name cannot be empty.");
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("\0")) {
            throw new UsageException("Enter a name or filename only, not a path: " + requestedName);
        }
        if (trimmed.equals(".") || trimmed.equals("..")) {
            throw new UsageException("Invalid filename: " + requestedName);
        }

        String originalFilename = source.getFileName().toString();
        String originalExtension = extensionWithDot(originalFilename);
        String targetFilename;
        if (hasExtension(trimmed)) {
            targetFilename = trimmed;
        } else {
            String frameLocation = frameLocationSuffix(originalFilename);
            if (frameLocation != null && !endsWithFrameLocation(trimmed, frameLocation)) {
                targetFilename = trimmed + "-" + frameLocation + originalExtension;
            } else {
                targetFilename = trimmed + originalExtension;
            }
        }

        try {
            return source.resolveSibling(targetFilename).normalize();
        } catch (InvalidPathException e) {
            throw new UsageException("Invalid filename: " + requestedName);
        }
    }

    private static boolean endsWithFrameLocation(String value, String frameLocation) {
        String lowerValue = value.toLowerCase(Locale.ROOT);
        String lowerLocation = frameLocation.toLowerCase(Locale.ROOT);
        return lowerValue.endsWith("-" + lowerLocation)
                || lowerValue.endsWith("_" + lowerLocation)
                || lowerValue.equals(lowerLocation);
    }

    static String frameLocationSuffix(String filename) {
        String stem = removeExtension(filename);
        String lowerStem = stem.toLowerCase(Locale.ROOT);
        int frameIndex = lowerStem.indexOf("frame");
        if (frameIndex < 0) {
            return null;
        }

        String suffix = stem.substring(frameIndex + "frame".length()).replaceFirst("^[-_]+", "");
        return suffix.isBlank() ? null : suffix;
    }

    private static void openWithSystemViewer(Path image) {
        List<String> command = systemOpenCommand(image);
        if (command.isEmpty()) {
            System.out.println("No known system image opener for this OS. Try --viewer terminal.");
            return;
        }

        try {
            new ProcessBuilder(command).start();
            System.out.println("Opened image: " + image.toAbsolutePath());
            restoreTerminalFocus();
        } catch (IOException e) {
            System.out.println("Could not open image automatically: " + e.getMessage());
        }
    }

    private static List<String> systemOpenCommand(Path image) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return List.of("open", image.toAbsolutePath().toString());
        }
        if (osName.contains("win")) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", image.toAbsolutePath().toString());
        }
        return List.of("xdg-open", image.toAbsolutePath().toString());
    }

    private static void restoreTerminalFocus() {
        if (!isMac()) {
            return;
        }

        String terminalApp = terminalApplicationName(System.getenv("TERM_PROGRAM"));
        try {
            Thread.sleep(700);
            Process process = new ProcessBuilder(
                    "osascript",
                    "-e",
                    "tell application " + appleScriptString(terminalApp) + " to activate"
            ).start();
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (IOException e) {
            System.out.println("Opened image, but could not return focus to " + terminalApp + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    static String terminalApplicationName(String termProgram) {
        if (termProgram == null || termProgram.isBlank()) {
            return "Terminal";
        }
        return switch (termProgram) {
            case "Apple_Terminal" -> "Terminal";
            case "iTerm.app" -> "iTerm";
            case "vscode" -> "Visual Studio Code";
            case "WezTerm" -> "WezTerm";
            case "WarpTerminal" -> "Warp";
            case "Ghostty" -> "Ghostty";
            case "Tabby" -> "Tabby";
            case "kitty" -> "kitty";
            case "Alacritty" -> "Alacritty";
            default -> "Terminal";
        };
    }

    private static String appleScriptString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static void printAsciiPreview(Path image, int width) {
        try {
            BufferedImage bufferedImage = ImageIO.read(image.toFile());
            if (bufferedImage == null) {
                System.out.println("Terminal preview is not available for this image format.");
                return;
            }

            int previewWidth = Math.min(width, bufferedImage.getWidth());
            int previewHeight = Math.max(1, (int) Math.round(bufferedImage.getHeight() * (previewWidth / (double) bufferedImage.getWidth()) * 0.5));
            for (int y = 0; y < previewHeight; y++) {
                StringBuilder line = new StringBuilder(previewWidth);
                for (int x = 0; x < previewWidth; x++) {
                    int sourceX = Math.min(bufferedImage.getWidth() - 1, (int) Math.floor(x * bufferedImage.getWidth() / (double) previewWidth));
                    int sourceY = Math.min(bufferedImage.getHeight() - 1, (int) Math.floor(y * bufferedImage.getHeight() / (double) previewHeight));
                    line.append(asciiPixel(bufferedImage.getRGB(sourceX, sourceY)));
                }
                System.out.println(line);
            }
        } catch (IOException e) {
            System.out.println("Could not print terminal preview: " + e.getMessage());
        }
    }

    private static char asciiPixel(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        int luminance = (int) Math.round(0.2126 * red + 0.7152 * green + 0.0722 * blue);
        int index = Math.min(ASCII_RAMP.length() - 1, Math.max(0, luminance * ASCII_RAMP.length() / 256));
        return ASCII_RAMP.charAt(index);
    }

    static Options parseOptions(String[] args) {
        Path peopleDir = DEFAULT_PEOPLE_DIR;
        Viewer viewer = Viewer.OPEN;
        int terminalWidth = DEFAULT_TERMINAL_WIDTH;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                help = true;
            } else if (arg.equals("--people-dir")) {
                peopleDir = expandPath(requireValue(args, ++i, "--people-dir"));
            } else if (arg.startsWith("--people-dir=")) {
                peopleDir = expandPath(arg.substring("--people-dir=".length()));
            } else if (arg.equals("--viewer")) {
                viewer = parseViewer(requireValue(args, ++i, "--viewer"));
            } else if (arg.startsWith("--viewer=")) {
                viewer = parseViewer(arg.substring("--viewer=".length()));
            } else if (arg.equals("--terminal-preview")) {
                viewer = Viewer.TERMINAL;
            } else if (arg.equals("--no-open")) {
                viewer = Viewer.NONE;
            } else if (arg.equals("--terminal-width")) {
                terminalWidth = parseTerminalWidth(requireValue(args, ++i, "--terminal-width"));
            } else if (arg.startsWith("--terminal-width=")) {
                terminalWidth = parseTerminalWidth(arg.substring("--terminal-width=".length()));
            } else {
                throw new UsageException("Unknown option: " + arg);
            }
        }

        return new Options(peopleDir.toAbsolutePath().normalize(), viewer, terminalWidth, help);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new UsageException("Missing value for " + option);
        }
        return args[index];
    }

    private static Viewer parseViewer(String value) {
        try {
            return Viewer.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UsageException("Invalid viewer '" + value + "'. Use open, terminal, both, or none.");
        }
    }

    private static int parseTerminalWidth(String value) {
        try {
            int width = Integer.parseInt(value);
            if (width < MIN_TERMINAL_WIDTH || width > MAX_TERMINAL_WIDTH) {
                throw new UsageException("--terminal-width must be between " + MIN_TERMINAL_WIDTH + " and " + MAX_TERMINAL_WIDTH);
            }
            return width;
        } catch (NumberFormatException e) {
            throw new UsageException("--terminal-width must be a number.");
        }
    }

    private static Path expandPath(String value) {
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2));
        }
        return Path.of(value);
    }

    private static boolean hasExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 && dot < filename.length() - 1;
    }

    private static String extensionWithDot(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private static String extensionWithoutDot(String filename) {
        String extension = extensionWithDot(filename);
        return extension.isBlank() ? "" : extension.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String removeExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }

    private static void printHelp() {
        System.out.println("""
                Usage:
                  java --enable-preview src/main/java/net/lckx/video/ReviewPersonCandidates.java [options]

                Reviews generated person candidate pictures whose filenames still contain "frame".
                For each image, enter a person name to rename it while keeping the frame location,
                or delete it if it is not a useful reference picture.

                Examples:
                  java --enable-preview src/main/java/net/lckx/video/ReviewPersonCandidates.java
                  java --enable-preview src/main/java/net/lckx/video/ReviewPersonCandidates.java --viewer both
                  java --enable-preview src/main/java/net/lckx/video/ReviewPersonCandidates.java --viewer terminal

                Options:
                  --people-dir <path>       People library. Default: ./video-people
                  --viewer <mode>           Image display mode: open, terminal, both, none. Default: open
                  --terminal-preview        Shortcut for --viewer terminal
                  --no-open                 Shortcut for --viewer none
                  --terminal-width <n>      ASCII terminal preview width. Default: 80
                  --help                    Show this help

                Prompt actions:
                  Miranda                   Renames frame-01-01m26s.jpg to Miranda-01-01m26s.jpg
                  Miranda-01-01m26s.jpg     Renames to that exact filename
                  d                         Deletes the image after confirmation
                  s                         Skips the image
                  q                         Quits

                Terminal image display:
                  --viewer open opens the real image in the system image viewer, for example Preview on macOS.
                                On macOS, focus is returned to the terminal before the prompt is shown.
                  --viewer terminal prints an ASCII preview directly in the terminal.
                  --viewer both does both.
                """);
    }

    record Options(Path peopleDir, Viewer viewer, int terminalWidth, boolean help) {
    }

    enum Viewer {
        OPEN,
        TERMINAL,
        BOTH,
        NONE
    }

    enum ReviewAction {
        RENAMED,
        DELETED,
        SKIP,
        QUIT
    }

    static class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
