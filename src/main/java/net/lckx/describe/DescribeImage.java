package net.lckx.describe;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Describes visible content in one image with a local Ollama vision model.
 *
 * Known-person references are read from the same ./video-people library used by DescribeVideo.
 */
public class DescribeImage {
    private static final String DEFAULT_MODEL = "qwen2.5vl:7b";
    private static final String DEFAULT_HOST = "http://localhost:11434";
    private static final int DEFAULT_TIMEOUT_MINUTES = 15;
    private static final int MAX_TIMEOUT_MINUTES = 120;
    private static final Path DEFAULT_PEOPLE_DIR = Path.of(System.getProperty("user.dir"), "video-people").toAbsolutePath().normalize();
    private static final int DEFAULT_MAX_PERSON_REFERENCES = 8;
    private static final int MAX_PERSON_REFERENCES = 50;
    private static final int PERSON_REFERENCE_WIDTH = 384;
    private static final String DEFAULT_PERSON_RECOGNITION = "auto";
    private static final List<String> SUPPORTED_PERSON_RECOGNITION = List.of("auto", "face", "ollama", "off");
    private static final String DEFAULT_FACE_RECOGNITION_PYTHON = defaultFaceRecognitionPython();
    private static final double DEFAULT_FACE_RECOGNITION_TOLERANCE = 0.6;
    private static final Path DEFAULT_FACE_RECOGNITION_SCRIPT = Path.of(System.getProperty("user.dir"), "src/main/python/net/lckx/video/face_recognize.py").toAbsolutePath().normalize();
    private static final Pattern GENERATED_PERSON_CANDIDATE = Pattern.compile("frame-\\d+-[0-9m]+s(?:-\\d+px)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern KNOWN_PERSON_FRAME_SUFFIX = Pattern.compile("[_-](?:\\d+[-_])?[0-9m]+s(?:-\\d+px)?$", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) {
        int exitCode = new DescribeImage().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        Path workDir = null;
        Options options = null;
        PersonRecognitionPlan personRecognition = PersonRecognitionPlan.disabled();

        try {
            options = parseOptions(args);
            if (options.addPersonRequest() != null) {
                addKnownPerson(options.peopleDir(), options.addPersonRequest());
                return 0;
            }

            if (options.imagePath() == null) {
                options = options.withImagePath(promptForImagePath());
            }

            validateImagePath(options.imagePath());

            RunTimer timer = RunTimer.start();
            timer.log("Starting image analysis.");
            long ollamaStartedAt = System.nanoTime();
            timer.log("Checking Ollama connection...");
            OllamaClient ollama = new OllamaClient(options.ollamaHost(), options.model(), options.requestTimeout());
            ollama.ping();
            timer.log("Ollama connection ready in " + formatDuration(Duration.ofNanos(System.nanoTime() - ollamaStartedAt)) + ".");

            long peopleStartedAt = System.nanoTime();
            KnownPeople knownPeople = KnownPeople.load(options.peopleDir(), options.maxPersonReferences());
            if (!knownPeople.isEmpty()) {
                timer.log("Loaded known people in " + formatDuration(Duration.ofNanos(System.nanoTime() - peopleStartedAt)) + ".");
            }

            workDir = Files.createTempDirectory("image-description-");
            personRecognition = preparePersonRecognition(options, knownPeople, workDir, timer);

            Optional<ImageSize> imageSize = readImageSize(options.imagePath());

            System.out.println();
            System.out.println("Image: " + options.imagePath().toAbsolutePath());
            imageSize.ifPresent(size -> System.out.println("Dimensions: " + size.width() + " x " + size.height() + " px"));
            System.out.println("Ollama timeout: " + options.requestTimeout().toMinutes() + " minutes");
            System.out.println("Model: " + options.model() + " via " + options.ollamaHost());
            if (!knownPeople.isEmpty()) {
                System.out.println("Known people: " + knownPeople.names());
                System.out.println("Known people recognition: " + personRecognition.description());
            } else if (options.maxPersonReferences() == 0) {
                System.out.println("Known people comparison: disabled");
            }
            System.out.println();

            FaceRecognitionResult faceRecognition = FaceRecognitionResult.empty();
            if (personRecognition.faceRecognizer() != null) {
                long faceStartedAt = System.nanoTime();
                timer.log("Running local face recognition...");
                faceRecognition = personRecognition.faceRecognizer().recognize(options.imagePath());
                timer.log("Local face recognition finished in "
                        + formatDuration(Duration.ofNanos(System.nanoTime() - faceStartedAt))
                        + "; detected " + faceRecognition.faceCount() + " face(s)"
                        + (faceRecognition.hasMatches() ? "; matched " + faceRecognition.namesText() : "")
                        + ".");
            }

            long descriptionStartedAt = System.nanoTime();
            timer.log("Describing image...");
            String description = ollama.describeImage(
                    options.imagePath(),
                    personRecognition.ollamaKnownPeople(),
                    faceRecognition
            );
            timer.log("Built image description in " + formatDuration(Duration.ofNanos(System.nanoTime() - descriptionStartedAt)) + ".");

            System.out.println();
            System.out.println("=== Image description ===");
            System.out.println(removeAbsentContent(description).strip());

            if (options.showDetails() && faceRecognition.faceCount() > 0) {
                System.out.println();
                System.out.println("=== Face recognition details ===");
                System.out.println("Detected faces: " + faceRecognition.faceCount());
                if (faceRecognition.hasMatches()) {
                    System.out.println("Matched names: " + faceRecognition.namesText());
                }
            }

            System.out.println();
            timer.log("Total processing time: " + timer.elapsedText() + ".");
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
            System.err.println("Interrupted while describing image.");
            return 130;
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            printSetupHelp();
            return 1;
        } finally {
            try {
                personRecognition.close();
            } catch (IOException e) {
                System.err.println("Warning: could not stop local face recognition helper: " + e.getMessage());
            }
            if (workDir != null) {
                try {
                    deleteRecursively(workDir);
                } catch (IOException e) {
                    System.err.println("Warning: could not clean up temporary image analysis files in " + workDir + ": " + e.getMessage());
                }
            }
        }
    }

    static Options parseOptions(String[] args) {
        return parseOptions(args, System.getenv("OLLAMA_VISION_MODEL"), System.getenv("OLLAMA_HOST"));
    }

    static Options parseOptions(String[] args, String envModel, String envHost) {
        String model = envModel == null || envModel.isBlank() ? DEFAULT_MODEL : envModel.trim();
        URI host = parseHost(envHost == null || envHost.isBlank() ? DEFAULT_HOST : envHost.trim());
        Duration requestTimeout = Duration.ofMinutes(DEFAULT_TIMEOUT_MINUTES);
        Path peopleDir = DEFAULT_PEOPLE_DIR;
        int maxPersonReferences = DEFAULT_MAX_PERSON_REFERENCES;
        String personRecognition = DEFAULT_PERSON_RECOGNITION;
        String faceRecognitionPython = DEFAULT_FACE_RECOGNITION_PYTHON;
        Path faceRecognitionScript = DEFAULT_FACE_RECOGNITION_SCRIPT;
        double faceRecognitionTolerance = DEFAULT_FACE_RECOGNITION_TOLERANCE;
        AddPersonRequest addPersonRequest = null;
        boolean showDetails = false;
        Path imagePath = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                throw new HelpException();
            } else if (arg.equals("--model")) {
                model = requireValue(args, ++i, "--model");
            } else if (arg.startsWith("--model=")) {
                model = valueAfterEquals(arg, "--model");
            } else if (arg.equals("--host")) {
                host = parseHost(requireValue(args, ++i, "--host"));
            } else if (arg.startsWith("--host=")) {
                host = parseHost(valueAfterEquals(arg, "--host"));
            } else if (arg.equals("--timeout-minutes")) {
                requestTimeout = Duration.ofMinutes(parseBoundedInt(requireValue(args, ++i, "--timeout-minutes"), "--timeout-minutes", 1, MAX_TIMEOUT_MINUTES));
            } else if (arg.startsWith("--timeout-minutes=")) {
                requestTimeout = Duration.ofMinutes(parseBoundedInt(valueAfterEquals(arg, "--timeout-minutes"), "--timeout-minutes", 1, MAX_TIMEOUT_MINUTES));
            } else if (arg.equals("--people-dir")) {
                peopleDir = expandHomePath(requireValue(args, ++i, "--people-dir"));
            } else if (arg.startsWith("--people-dir=")) {
                peopleDir = expandHomePath(valueAfterEquals(arg, "--people-dir"));
            } else if (arg.equals("--max-person-refs")) {
                maxPersonReferences = parseBoundedInt(requireValue(args, ++i, "--max-person-refs"), "--max-person-refs", 0, MAX_PERSON_REFERENCES);
            } else if (arg.startsWith("--max-person-refs=")) {
                maxPersonReferences = parseBoundedInt(valueAfterEquals(arg, "--max-person-refs"), "--max-person-refs", 0, MAX_PERSON_REFERENCES);
            } else if (arg.equals("--no-known-people")) {
                maxPersonReferences = 0;
                personRecognition = "off";
            } else if (arg.equals("--person-recognition")) {
                personRecognition = requireValue(args, ++i, "--person-recognition").trim().toLowerCase(Locale.ROOT);
            } else if (arg.startsWith("--person-recognition=")) {
                personRecognition = valueAfterEquals(arg, "--person-recognition").trim().toLowerCase(Locale.ROOT);
            } else if (arg.equals("--face-recognition-python")) {
                faceRecognitionPython = expandHome(requireValue(args, ++i, "--face-recognition-python"));
            } else if (arg.startsWith("--face-recognition-python=")) {
                faceRecognitionPython = expandHome(valueAfterEquals(arg, "--face-recognition-python"));
            } else if (arg.equals("--face-recognition-script")) {
                faceRecognitionScript = expandHomePath(requireValue(args, ++i, "--face-recognition-script"));
            } else if (arg.startsWith("--face-recognition-script=")) {
                faceRecognitionScript = expandHomePath(valueAfterEquals(arg, "--face-recognition-script"));
            } else if (arg.equals("--face-recognition-tolerance")) {
                faceRecognitionTolerance = parseDouble(requireValue(args, ++i, "--face-recognition-tolerance"), "--face-recognition-tolerance", 0.1, 1.0);
            } else if (arg.startsWith("--face-recognition-tolerance=")) {
                faceRecognitionTolerance = parseDouble(valueAfterEquals(arg, "--face-recognition-tolerance"), "--face-recognition-tolerance", 0.1, 1.0);
            } else if (arg.equals("--add-person")) {
                String name = requireValue(args, ++i, "--add-person");
                Path referenceImagePath = expandHomePath(requireValue(args, ++i, "--add-person image"));
                addPersonRequest = new AddPersonRequest(name, referenceImagePath);
            } else if (arg.equals("--details")) {
                showDetails = true;
            } else if (arg.startsWith("-")) {
                throw new UsageException("Unknown option: " + arg);
            } else if (imagePath == null) {
                imagePath = Path.of(arg);
            } else {
                throw new UsageException("Only one image file can be described at a time.");
            }
        }

        if (addPersonRequest != null && imagePath != null) {
            throw new UsageException("--add-person is a standalone command and cannot be combined with an image file.");
        }
        if (model.isBlank()) {
            throw new UsageException("Model name cannot be empty.");
        }
        if (!SUPPORTED_PERSON_RECOGNITION.contains(personRecognition)) {
            throw new UsageException("--person-recognition must be one of: " + String.join(", ", SUPPORTED_PERSON_RECOGNITION));
        }
        if (peopleDir.toString().isBlank()) {
            throw new UsageException("--people-dir cannot be empty.");
        }

        return new Options(imagePath, model, host, requestTimeout, peopleDir, maxPersonReferences,
                personRecognition, faceRecognitionPython, faceRecognitionScript, faceRecognitionTolerance,
                addPersonRequest, showDetails);
    }

    private static String defaultFaceRecognitionPython() {
        Path installedVenvPython = Path.of(System.getProperty("user.home"), ".venvs/video-face-recognition/bin/python");
        if (Files.isRegularFile(installedVenvPython) && Files.isExecutable(installedVenvPython)) {
            return installedVenvPython.toString();
        }
        return "python3";
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

    private static String expandHome(String value) {
        return expandHomePath(value).toString();
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

    private static double parseDouble(String value, String option, double min, double max) {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number) || number < min || number > max) {
                throw new UsageException(option + " must be between " + min + " and " + max + ".");
            }
            return number;
        } catch (NumberFormatException e) {
            throw new UsageException(option + " must be a number.");
        }
    }

    static URI parseHost(String host) {
        String normalized = host.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URI uri = URI.create(normalized);
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("missing host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new UsageException("Invalid Ollama host: " + host);
        }
    }

    private static Path promptForImagePath() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("Image file: ");
            System.out.flush();
            if (!scanner.hasNextLine()) {
                throw new UsageException("Missing image file.");
            }
            String input = scanner.nextLine().trim();
            if (input.startsWith("\"") && input.endsWith("\"") && input.length() >= 2) {
                input = input.substring(1, input.length() - 1).trim();
            } else if (input.startsWith("'") && input.endsWith("'") && input.length() >= 2) {
                input = input.substring(1, input.length() - 1).trim();
            }
            if (!input.isEmpty()) {
                return expandHomePath(input);
            }
            System.out.println("Please enter a path to an image file (or press Ctrl+C to cancel).");
        }
    }

    private static void validateImagePath(Path imagePath) {
        if (!Files.exists(imagePath)) {
            throw new UsageException("Image file not found: " + imagePath);
        }
        if (!Files.isRegularFile(imagePath)) {
            throw new UsageException("Not a regular image file: " + imagePath);
        }
        if (!Files.isReadable(imagePath)) {
            throw new UsageException("Image file is not readable: " + imagePath);
        }
    }

    private static Optional<ImageSize> readImageSize(Path imagePath) throws IOException {
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) {
            return Optional.empty();
        }
        return Optional.of(new ImageSize(image.getWidth(), image.getHeight()));
    }

    private static PersonRecognitionPlan preparePersonRecognition(Options options, KnownPeople knownPeople, Path workDir, RunTimer timer)
            throws IOException, InterruptedException {
        if (knownPeople.isEmpty() || options.personRecognition().equals("off")) {
            return PersonRecognitionPlan.disabled();
        }
        if (options.personRecognition().equals("ollama")) {
            return PersonRecognitionPlan.ollama(knownPeople);
        }

        long startedAt = System.nanoTime();
        try {
            timer.log("Starting local face recognition helper...");
            FaceRecognitionServer faceRecognizer = FaceRecognitionServer.start(
                    options.faceRecognitionPython(),
                    options.faceRecognitionScript(),
                    options.faceRecognitionTolerance(),
                    knownPeople,
                    workDir
            );
            timer.log("Local face recognition ready in "
                    + formatDuration(Duration.ofNanos(System.nanoTime() - startedAt))
                    + "; encoded " + faceRecognizer.referenceFaceCount() + " reference face(s).");
            return PersonRecognitionPlan.face(faceRecognizer);
        } catch (IOException e) {
            if (options.personRecognition().equals("face")) {
                throw e;
            }
            timer.log("Local face recognition unavailable (" + truncate(e.getMessage(), 300)
                    + "); falling back to Ollama known-person comparison.");
            return PersonRecognitionPlan.ollama(knownPeople);
        }
    }

    private static void addKnownPerson(Path peopleDir, AddPersonRequest request) throws IOException {
        String personName = request.name().trim();
        if (personName.isBlank()) {
            throw new UsageException("Person name cannot be empty.");
        }
        if (!Files.exists(request.imagePath())) {
            throw new UsageException("Reference image not found: " + request.imagePath());
        }
        if (!Files.isRegularFile(request.imagePath())) {
            throw new UsageException("Reference image is not a regular file: " + request.imagePath());
        }

        Files.createDirectories(peopleDir);
        Path destination = uniqueReferencePath(peopleDir, personName);
        normalizeReferenceImage(request.imagePath(), destination);

        System.out.println("Saved reference picture for " + personName + ": " + destination);
        System.out.println("Future image and video descriptions will use this local reference when comparing visible people.");
    }

    private static void normalizeReferenceImage(Path source, Path destination) throws IOException {
        BufferedImage sourceImage = ImageIO.read(source.toFile());
        if (sourceImage == null) {
            throw new IOException("Could not read reference image: " + source);
        }

        int width = Math.min(PERSON_REFERENCE_WIDTH, sourceImage.getWidth());
        int height = Math.max(1, (int) Math.round(sourceImage.getHeight() * (width / (double) sourceImage.getWidth())));
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(sourceImage, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        if (!ImageIO.write(output, "jpg", destination.toFile())) {
            throw new IOException("Could not write reference image: " + destination);
        }
    }

    private static Path uniqueReferencePath(Path peopleDir, String personName) {
        String sourceName = sanitizeFilename(personName);
        Path destination = peopleDir.resolve(sourceName + ".jpg");
        int counter = 2;
        while (Files.exists(destination)) {
            destination = peopleDir.resolve(sourceName + "-" + counter + ".jpg");
            counter++;
        }
        return destination;
    }

    static String sanitizeFilename(String value) {
        String sanitized = value.strip().replaceAll("[\\\\/:*?\"<>|]", "_");
        sanitized = sanitized.replaceAll("\\s+", " ");
        return sanitized.isBlank() ? "unnamed" : sanitized;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    static String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1_000) {
            return millis + "ms";
        }
        if (millis < 10_000) {
            return String.format(Locale.ROOT, "%.1fs", millis / 1_000.0);
        }

        long seconds = duration.toSeconds();
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        if (hours > 0) {
            return "%dh %dm %ds".formatted(hours, remainingMinutes, remainingSeconds);
        }
        if (minutes > 0) {
            return "%dm %ds".formatted(minutes, remainingSeconds);
        }
        return seconds + "s";
    }

    static String formatElapsedTimestamp(Duration duration) {
        long totalMillis = duration.toMillis();
        long hours = totalMillis / 3_600_000;
        long minutes = (totalMillis / 60_000) % 60;
        long seconds = (totalMillis / 1_000) % 60;
        long remainingMillis = totalMillis % 1_000;
        if (hours > 0) {
            return "+%02d:%02d:%02d.%03d".formatted(hours, minutes, seconds, remainingMillis);
        }
        return "+%02d:%02d.%03d".formatted(minutes, seconds, remainingMillis);
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java --enable-preview src/main/java/net/lckx/video/DescribeImage.java <image-file> [options]

                Options:
                  --model <name>       Ollama vision model to use. Default: qwen2.5vl:7b
                  --host <url>         Ollama host. Default: http://localhost:11434
                  --timeout-minutes <n> Ollama request timeout. Default: 15, max: 120
                  --people-dir <path>  Known people library. Default: ./video-people
                  --add-person <name> <image>
                                       Add a reference picture for a known person, then exit
                  --max-person-refs <n>
                                       Max known-person reference pictures sent to Ollama. Default: 8
                  --no-known-people    Do not compare the image with known people
                  --person-recognition <mode>
                                       Known-person method: auto, face, ollama, off. Default: auto
                  --face-recognition-python <path>
                                       Python executable for local face_recognition.
                                       Default: ~/.venvs/video-face-recognition/bin/python when present, else python3
                  --face-recognition-script <path>
                                       Helper script path. Default: src/main/python/net/lckx/video/face_recognize.py
                  --face-recognition-tolerance <n>
                                       Local face match threshold. Default: 0.6; lower is stricter
                  --details            Print face-recognition details when available
                  --help               Show this help

                Environment variables:
                  OLLAMA_VISION_MODEL  Overrides the default model
                  OLLAMA_HOST          Overrides the default Ollama host

                Example:
                  java --enable-preview src/main/java/net/lckx/video/DescribeImage.java ~/Pictures/photo.jpg
                """);
    }

    private static void printSetupHelp() {
        System.err.println("""
                Setup:
                  brew install ollama
                  ollama pull qwen2.5vl:7b
                  ollama serve

                Optional local face recognition setup:
                  python3 -m venv ~/.venvs/video-face-recognition
                  ~/.venvs/video-face-recognition/bin/pip install "setuptools<81" face_recognition
                  # then pass --face-recognition-python ~/.venvs/video-face-recognition/bin/python

                This tool describes visible image content and can use renamed pictures in
                ./video-people as known-person references. If local face recognition is unavailable,
                the default auto mode falls back to Ollama comparison sheets.
                """);
    }

    static String removeAbsentContent(String summary) {
        List<String> lines = summary.lines().toList();
        StringBuilder cleaned = new StringBuilder();
        String heading = null;
        List<String> content = new ArrayList<>();

        for (String line : lines) {
            if (isSummaryHeading(line)) {
                appendSummaryBlock(cleaned, heading, content);
                heading = line.strip();
                content = new ArrayList<>();
            } else if (!describesAbsentContent(line)) {
                content.add(line);
            }
        }
        appendSummaryBlock(cleaned, heading, content);

        return cleaned.toString().strip();
    }

    private static void appendSummaryBlock(StringBuilder cleaned, String heading, List<String> content) {
        boolean hasContent = content.stream().anyMatch(line -> !line.isBlank());
        if (heading != null && !hasContent) {
            return;
        }

        if (!cleaned.isEmpty() && (heading != null || hasContent)) {
            cleaned.append("\n\n");
        }
        if (heading != null) {
            cleaned.append(heading).append('\n');
        }
        for (String line : content) {
            cleaned.append(line).append('\n');
        }
    }

    private static boolean isSummaryHeading(String line) {
        String normalized = line.strip()
                .replace("*", "")
                .replace("#", "")
                .replace(":", "")
                .strip()
                .toLowerCase(Locale.ROOT);
        return List.of("summary", "people", "activities", "places/scenes", "notable objects", "text", "tags", "confidence")
                .contains(normalized);
    }

    private static boolean describesAbsentContent(String line) {
        String normalized = line.strip().toLowerCase(Locale.ROOT);
        return normalized.contains("not visible")
                || normalized.contains("not explicitly visible")
                || normalized.contains("not observed")
                || normalized.contains("not seen")
                || normalized.contains("not available")
                || normalized.contains("unavailable")
                || normalized.contains("not provided")
                || normalized.contains("no text")
                || normalized.contains("none explicitly")
                || normalized.contains("none mentioned")
                || normalized.contains("none visible")
                || normalized.contains(": none")
                || normalized.matches("[-* ]*(none|unknown|unclear|n/a)\\.?");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    record Options(Path imagePath, String model, URI ollamaHost, Duration requestTimeout,
                   Path peopleDir, int maxPersonReferences, String personRecognition,
                   String faceRecognitionPython, Path faceRecognitionScript, double faceRecognitionTolerance,
                   AddPersonRequest addPersonRequest, boolean showDetails) {
        Options withImagePath(Path newImagePath) {
            return new Options(newImagePath, model, ollamaHost, requestTimeout, peopleDir,
                    maxPersonReferences, personRecognition, faceRecognitionPython,
                    faceRecognitionScript, faceRecognitionTolerance, addPersonRequest, showDetails);
        }
    }

    record AddPersonRequest(String name, Path imagePath) {
    }

    private record ImageSize(int width, int height) {
    }

    private static final class RunTimer {
        private final long startedAtNanos;

        private RunTimer(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }

        static RunTimer start() {
            return new RunTimer(System.nanoTime());
        }

        void log(String message) {
            System.out.println("[" + formatElapsedTimestamp(elapsed()) + "] " + message);
        }

        Duration elapsed() {
            return Duration.ofNanos(System.nanoTime() - startedAtNanos);
        }

        String elapsedText() {
            return formatDuration(elapsed());
        }
    }

    static class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    static class HelpException extends UsageException {
        HelpException() {
            super("Image description tool");
        }
    }

    static class OllamaRequestTimeoutException extends IOException {
        OllamaRequestTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record FaceRecognitionResult(List<String> names, int faceCount) {
        static FaceRecognitionResult empty() {
            return new FaceRecognitionResult(List.of(), 0);
        }

        boolean hasMatches() {
            return !names.isEmpty();
        }

        String namesText() {
            return String.join(", ", names);
        }
    }

    record PersonRecognitionPlan(KnownPeople ollamaKnownPeople, FaceRecognitionServer faceRecognizer, String description)
            implements AutoCloseable {
        static PersonRecognitionPlan disabled() {
            return new PersonRecognitionPlan(KnownPeople.empty(), null, "disabled");
        }

        static PersonRecognitionPlan ollama(KnownPeople knownPeople) {
            return new PersonRecognitionPlan(knownPeople, null, "Ollama comparison sheet; slower but no extra setup");
        }

        static PersonRecognitionPlan face(FaceRecognitionServer faceRecognizer) {
            return new PersonRecognitionPlan(KnownPeople.empty(), faceRecognizer, "local face recognition; faster and avoids Ollama reference sheets");
        }

        @Override
        public void close() throws IOException {
            if (faceRecognizer != null) {
                faceRecognizer.close();
            }
        }
    }

    record KnownPersonReference(String name, Path imagePath) {
    }

    record KnownPeople(List<KnownPersonReference> references) {
        static KnownPeople empty() {
            return new KnownPeople(List.of());
        }

        static KnownPeople load(Path peopleDir, int maxReferences) throws IOException {
            if (maxReferences == 0 || !Files.isDirectory(peopleDir)) {
                return empty();
            }

            List<KnownPersonReference> references = new ArrayList<>();
            try (Stream<Path> imageFiles = Files.walk(peopleDir)) {
                for (Path imagePath : imageFiles
                        .filter(Files::isRegularFile)
                        .filter(KnownPeople::isReferenceImage)
                        .sorted()
                        .toList()) {
                    String name = referenceName(peopleDir, imagePath);
                    if (name.isBlank()) {
                        continue;
                    }
                    references.add(new KnownPersonReference(name, imagePath));
                    if (references.size() >= maxReferences) {
                        return new KnownPeople(List.copyOf(references));
                    }
                }
            }
            return new KnownPeople(List.copyOf(references));
        }

        boolean isEmpty() {
            return references.isEmpty();
        }

        String names() {
            return references.stream()
                    .map(KnownPersonReference::name)
                    .distinct()
                    .sorted()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        }

        String promptReferenceList() {
            StringBuilder text = new StringBuilder();
            for (KnownPersonReference reference : references) {
                text.append("- ")
                        .append(reference.name())
                        .append('\n');
            }
            return text.toString();
        }

        private static boolean isReferenceImage(Path path) {
            String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return filename.endsWith(".jpg")
                    || filename.endsWith(".jpeg")
                    || filename.endsWith(".png")
                    || filename.endsWith(".webp");
        }

        private static String referenceName(Path peopleDir, Path imagePath) {
            Path relative = peopleDir.relativize(imagePath);
            String filename = stripExtension(imagePath.getFileName().toString()).strip();
            if (isGeneratedCandidateName(filename)) {
                return "";
            }

            if (relative.getNameCount() >= 3 && relative.getName(0).toString().equals("known")) {
                return relative.getName(1).toString();
            }
            return stripSampleNumber(filename);
        }

        private static boolean isGeneratedCandidateName(String filename) {
            Matcher matcher = GENERATED_PERSON_CANDIDATE.matcher(filename);
            return matcher.matches();
        }

        private static String stripSampleNumber(String filename) {
            String withoutFrameLocation = KNOWN_PERSON_FRAME_SUFFIX.matcher(filename).replaceFirst("");
            return withoutFrameLocation.replaceFirst("[_-]\\d+$", "").strip();
        }
    }

    static final class FaceRecognitionServer implements AutoCloseable {
        private final Process process;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private final int referenceFaceCount;

        private FaceRecognitionServer(Process process, BufferedReader reader, BufferedWriter writer, int referenceFaceCount) {
            this.process = process;
            this.reader = reader;
            this.writer = writer;
            this.referenceFaceCount = referenceFaceCount;
        }

        static FaceRecognitionServer start(String python, Path script, double tolerance, KnownPeople knownPeople, Path workDir) throws IOException {
            if (!Files.isRegularFile(script)) {
                throw new IOException("face recognition helper script not found: " + script);
            }

            Path referencesFile = writeFaceRecognitionReferences(workDir, knownPeople.references());
            ProcessBuilder processBuilder = new ProcessBuilder(
                    python,
                    script.toString(),
                    "--server",
                    "--references", referencesFile.toString(),
                    "--tolerance", Double.toString(tolerance)
            );
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            ReadyLine ready;
            try {
                String readyLine = reader.readLine();
                if (readyLine == null) {
                    throw new IOException("local face recognition helper exited before it became ready");
                }
                ready = parseReadyLine(readyLine);
                if (ready.referenceFaceCount() == 0) {
                    throw new IOException("local face recognition found no usable faces in the known-person reference pictures");
                }
            } catch (IOException e) {
                process.destroyForcibly();
                reader.close();
                writer.close();
                throw e;
            }
            return new FaceRecognitionServer(process, reader, writer, ready.referenceFaceCount());
        }

        int referenceFaceCount() {
            return referenceFaceCount;
        }

        FaceRecognitionResult recognize(Path imagePath) throws IOException {
            writer.write(imagePath.toAbsolutePath().toString());
            writer.newLine();
            writer.flush();

            String line = reader.readLine();
            if (line == null) {
                throw new IOException("local face recognition helper stopped unexpectedly");
            }
            return parseMatchLine(line);
        }

        @Override
        public void close() throws IOException {
            if (!process.isAlive()) {
                return;
            }

            try {
                writer.write("__QUIT__");
                writer.newLine();
                writer.flush();
            } catch (IOException ignored) {
                // The process may already be exiting; destroy below if needed.
            }
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            } finally {
                reader.close();
                writer.close();
            }
        }

        private static Path writeFaceRecognitionReferences(Path workDir, List<KnownPersonReference> references) throws IOException {
            Path referencesFile = workDir.resolve("face-recognition-references.tsv");
            try (BufferedWriter referenceWriter = Files.newBufferedWriter(referencesFile, StandardCharsets.UTF_8)) {
                for (KnownPersonReference reference : references) {
                    referenceWriter.write(sanitizeProtocolField(reference.name()));
                    referenceWriter.write('\t');
                    referenceWriter.write(reference.imagePath().toAbsolutePath().toString());
                    referenceWriter.newLine();
                }
            }
            return referencesFile;
        }

        private static ReadyLine parseReadyLine(String line) throws IOException {
            String[] parts = line.split("\t", -1);
            if (parts.length < 2 || !parts[0].equals("READY")) {
                if (line.startsWith("ERROR\t")) {
                    throw new IOException("local face recognition setup failed: " + line.substring("ERROR\t".length()));
                }
                throw new IOException("unexpected local face recognition startup output: " + truncate(line, 300));
            }
            try {
                return new ReadyLine(Integer.parseInt(parts[1]));
            } catch (NumberFormatException e) {
                throw new IOException("unexpected local face recognition reference count: " + truncate(line, 300));
            }
        }

        static FaceRecognitionResult parseMatchLine(String line) throws IOException {
            String[] parts = line.split("\t", -1);
            if (parts.length < 2 || !parts[0].equals("MATCH")) {
                if (line.startsWith("ERROR\t")) {
                    throw new IOException("local face recognition failed: " + line.substring("ERROR\t".length()));
                }
                throw new IOException("unexpected local face recognition output: " + truncate(line, 300));
            }
            int faceCount;
            try {
                faceCount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("unexpected local face recognition face count: " + truncate(line, 300));
            }

            List<String> names = new ArrayList<>();
            for (int i = 2; i < parts.length; i++) {
                String name = parts[i].strip();
                if (!name.isBlank() && !names.contains(name)) {
                    names.add(name);
                }
            }
            return new FaceRecognitionResult(List.copyOf(names), faceCount);
        }

        private static String sanitizeProtocolField(String value) {
            return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').strip();
        }

        private record ReadyLine(int referenceFaceCount) {
        }
    }

    static class OllamaClient {
        private final URI host;
        private final String model;
        private final Duration requestTimeout;
        private final HttpClient httpClient;

        OllamaClient(URI host, String model, Duration requestTimeout) {
            this.host = host;
            this.model = model;
            this.requestTimeout = requestTimeout;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        void ping() throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(apiUri("/api/tags"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new IOException("Could not connect to Ollama at " + host + ". Is 'ollama serve' running?", e);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Ollama is reachable but returned HTTP " + response.statusCode() + ": " + truncate(response.body(), 600));
            }
        }

        String describeImage(Path imagePath, KnownPeople knownPeople, FaceRecognitionResult faceRecognition) throws IOException, InterruptedException {
            List<String> images = new ArrayList<>();

            String knownPeopleInstructions = "";
            String imageScope = "Describe only what is visible in the image.";
            if (knownPeople != null && !knownPeople.isEmpty()) {
                byte[] comparisonImage = buildKnownPeopleComparisonImage(imagePath, knownPeople.references());
                images.add(Base64.getEncoder().encodeToString(comparisonImage));
                imageScope = "Describe only what is visible in the IMAGE panel.";
                knownPeopleInstructions = """

                        The attached image is an analysis worksheet, not the original photo by itself. The panel labelled IMAGE
                        is the only source image. Smaller panels labelled REFERENCE are known-person examples:
                        %s
                        Describe only what is visible in the IMAGE panel. If a visible person in the IMAGE panel
                        clearly matches a named REFERENCE panel, use that person's name. If you are not confident,
                        describe the person generically. Never describe the worksheet, labels, comparison sheet, reference
                        panels, or reference pictures as image content. Do not invent names.
                        """.formatted(knownPeople.promptReferenceList());
            } else {
                images.add(Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath)));
            }

            String faceRecognitionInstructions = "";
            if (faceRecognition != null && faceRecognition.hasMatches()) {
                faceRecognitionInstructions = """

                        Local face recognition matched visible face(s) in this image to: %s.
                        Use these name(s) for matching visible people when it fits the image. Do not invent names
                        for other visible people.
                        """.formatted(faceRecognition.namesText());
            }

            String prompt = """
                    You are describing one personal image named "%s".

                    Start directly with the Summary heading. Do not write an introduction.
                    Write a practical description for organizing/searching photos. Mention only things that are visible
                    in the image. Omit empty sections entirely. Do not mention absent categories, negative facts, or
                    phrases like "not visible", "none", "unknown", or "not available". Ignore any mentions of
                    comparison sheets, worksheets, labels, reference panels, or image panels; those are analysis
                    artifacts and are not photo content.

                    %s Include visible people and likely age group, visible activities, place or scene, notable objects,
                    animals, vehicles, text, mood, and concise tags. Do not invent details.

                    Use these headings when they have supported content:
                    Summary:
                    People:
                    Activities:
                    Places/scenes:
                    Notable objects:
                    Text:
                    Tags:
                    Confidence:

                    Tags must contain only observed content, for example people, kids, beach, swimming, indoor, outdoor,
                    family, nature, pets, sports, party, vehicles, food, or travel when supported.
                    The Confidence section may say this is based on one image.
                    %s%s
                    """.formatted(imagePath.getFileName(), imageScope, knownPeopleInstructions, faceRecognitionInstructions);
            return generate(prompt, images).strip();
        }

        private static byte[] buildKnownPeopleComparisonImage(Path imagePath, List<KnownPersonReference> references) throws IOException {
            BufferedImage sourceImage = readImage(imagePath, "source image");
            List<KnownPersonImage> referenceImages = new ArrayList<>();
            for (KnownPersonReference reference : references) {
                referenceImages.add(new KnownPersonImage(reference.name(), readImage(reference.imagePath(), "known person reference " + reference.imagePath())));
            }

            int margin = 12;
            int labelHeight = 22;
            int maxImageWidth = 320;
            int referenceSize = 96;
            int referenceColumns = Math.min(4, Math.max(1, referenceImages.size()));
            int imageWidth = Math.min(maxImageWidth, sourceImage.getWidth());
            int imageHeight = Math.max(1, (int) Math.round(sourceImage.getHeight() * (imageWidth / (double) sourceImage.getWidth())));
            int referenceGridWidth = referenceColumns * referenceSize + (referenceColumns - 1) * margin;
            int canvasWidth = Math.max(imageWidth, referenceGridWidth) + margin * 2;
            int referenceRows = (int) Math.ceil(referenceImages.size() / (double) referenceColumns);
            int canvasHeight = margin + labelHeight + imageHeight + margin
                    + referenceRows * (labelHeight + referenceSize)
                    + Math.max(0, referenceRows - 1) * margin
                    + margin;

            BufferedImage sheet = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = sheet.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, canvasWidth, canvasHeight);

                Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, 14);
                Font smallLabelFont = new Font(Font.SANS_SERIF, Font.BOLD, 10);
                graphics.setFont(labelFont);

                int imageX = (canvasWidth - imageWidth) / 2;
                int y = margin;
                drawLabel(graphics, "IMAGE", imageX, y, imageWidth);
                y += labelHeight;
                drawFittedImage(graphics, sourceImage, imageX, y, imageWidth, imageHeight);
                y += imageHeight + margin;

                graphics.setFont(smallLabelFont);
                int gridX = (canvasWidth - referenceGridWidth) / 2;
                for (int i = 0; i < referenceImages.size(); i++) {
                    KnownPersonImage reference = referenceImages.get(i);
                    int column = i % referenceColumns;
                    int row = i / referenceColumns;
                    int x = gridX + column * (referenceSize + margin);
                    int itemY = y + row * (labelHeight + referenceSize + margin);
                    drawLabel(graphics, fitLabel(graphics, "REFERENCE: " + reference.name(), referenceSize), x, itemY, referenceSize);
                    drawFittedImage(graphics, reference.image(), x, itemY + labelHeight, referenceSize, referenceSize);
                }
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(sheet, "jpg", output)) {
                throw new IOException("Could not encode known-person comparison image.");
            }
            return output.toByteArray();
        }

        private static BufferedImage readImage(Path path, String description) throws IOException {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                throw new IOException("Could not read " + description + ": " + path);
            }
            return image;
        }

        private static void drawLabel(Graphics2D graphics, String label, int x, int y, int width) {
            FontMetrics metrics = graphics.getFontMetrics();
            int textX = x + Math.max(0, (width - metrics.stringWidth(label)) / 2);
            graphics.setColor(Color.BLACK);
            graphics.drawString(label, textX, y + metrics.getAscent());
        }

        private static String fitLabel(Graphics2D graphics, String label, int width) {
            FontMetrics metrics = graphics.getFontMetrics();
            if (metrics.stringWidth(label) <= width) {
                return label;
            }
            String ellipsis = "...";
            for (int end = label.length(); end > 0; end--) {
                String candidate = label.substring(0, end).stripTrailing() + ellipsis;
                if (metrics.stringWidth(candidate) <= width) {
                    return candidate;
                }
            }
            return ellipsis;
        }

        private static void drawFittedImage(Graphics2D graphics, BufferedImage image, int x, int y, int boxWidth, int boxHeight) {
            double scale = Math.min(boxWidth / (double) image.getWidth(), boxHeight / (double) image.getHeight());
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int imageX = x + (boxWidth - width) / 2;
            int imageY = y + (boxHeight - height) / 2;

            graphics.setColor(Color.WHITE);
            graphics.fillRect(x, y, boxWidth, boxHeight);
            graphics.drawImage(image, imageX, imageY, width, height, null);
            graphics.setColor(Color.DARK_GRAY);
            graphics.drawRect(x, y, boxWidth - 1, boxHeight - 1);
        }

        private record KnownPersonImage(String name, BufferedImage image) {
        }

        private String generate(String prompt, List<String> base64Images) throws IOException, InterruptedException {
            String json = buildGenerateRequest(prompt, base64Images);
            HttpRequest request = HttpRequest.newBuilder(apiUri("/api/generate"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                throw new OllamaRequestTimeoutException("Ollama request timed out after " + requestTimeout.toMinutes()
                        + " minutes.", e);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Ollama returned HTTP " + response.statusCode() + ": " + truncate(response.body(), 1200));
            }

            String generated = jsonString(response.body(), "response");
            if (generated.isBlank()) {
                String error = jsonString(response.body(), "error");
                if (!error.isBlank()) {
                    throw new IOException("Ollama error: " + error);
                }
                throw new IOException("Ollama returned no generated response: " + truncate(response.body(), 1200));
            }
            return generated;
        }

        private String buildGenerateRequest(String prompt, List<String> base64Images) {
            StringBuilder json = new StringBuilder();
            json.append('{');
            json.append("\"model\":\"").append(jsonEscape(model)).append("\",");
            json.append("\"prompt\":\"").append(jsonEscape(prompt)).append("\",");
            json.append("\"stream\":false,");
            json.append("\"options\":{\"temperature\":0.1}");
            if (!base64Images.isEmpty()) {
                json.append(",\"images\":[");
                for (int i = 0; i < base64Images.size(); i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    json.append('"').append(base64Images.get(i)).append('"');
                }
                json.append(']');
            }
            json.append('}');
            return json.toString();
        }

        private URI apiUri(String path) {
            return URI.create(host.toString() + path);
        }
    }

    static String jsonString(String json, String key) {
        return JsonHelpers.jsonString(json, key);
    }

    static String jsonEscape(String value) {
        return JsonHelpers.jsonEscape(value);
    }

    static String unescapeJsonString(String value) {
        return JsonHelpers.unescapeJsonString(value);
    }
}
