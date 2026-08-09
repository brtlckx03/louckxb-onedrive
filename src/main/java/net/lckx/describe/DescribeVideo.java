package net.lckx.describe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Describes visible content in a video by sampling frames and sending them to a local Ollama vision model.
 *
 * Requires:
 * - ffmpeg and ffprobe on PATH
 * - Ollama running locally with a vision model, for example: ollama pull qwen2.5vl:7b
 */
public class DescribeVideo {
    private static final String DEFAULT_MODEL = "qwen2.5vl:7b";
    private static final String DEFAULT_HOST = "http://localhost:11434";
    private static final int DEFAULT_FRAME_COUNT = 8;
    private static final int MAX_FRAME_COUNT = 50;
    private static final int MAX_INTERVAL_SAMPLE_COUNT = 500;
    private static final int DEFAULT_IMAGE_WIDTH = 512;
    private static final int MIN_IMAGE_WIDTH = 128;
    private static final int MAX_IMAGE_WIDTH = 2048;
    private static final int AUTO_MIN_IMAGE_WIDTH = 128;
    private static final int DEFAULT_TIMEOUT_MINUTES = 15;
    private static final int MAX_TIMEOUT_MINUTES = 120;
    private static final Duration SLOW_FRAME_THRESHOLD = Duration.ofMinutes(2);
    private static final String DEFAULT_TRANSCRIBER = "auto";
    private static final String DEFAULT_SPEECH_MODEL = "small";
    private static final String DEFAULT_SPEECH_LANGUAGE = "auto";
    private static final int DEFAULT_SPEECH_TIMEOUT_MINUTES = 30;
    private static final int MAX_SPEECH_TIMEOUT_MINUTES = 240;
    private static final List<String> SUPPORTED_TRANSCRIBERS = List.of("auto", "whisper", "whisper-cli", "whisper-cpp");
    private static final Path DEFAULT_PEOPLE_DIR = Path.of(System.getProperty("user.dir"), "video-people").toAbsolutePath().normalize();
    private static final int DEFAULT_MAX_PERSON_REFERENCES = 8;
    private static final int MAX_PERSON_REFERENCES = 50;
    private static final int PERSON_REFERENCE_WIDTH = 384;
    private static final String DEFAULT_PERSON_RECOGNITION = "auto";
    private static final List<String> SUPPORTED_PERSON_RECOGNITION = List.of("auto", "face", "ollama", "off");
    private static final String DEFAULT_FACE_RECOGNITION_PYTHON = "python3";
    private static final double DEFAULT_FACE_RECOGNITION_TOLERANCE = 0.6;
    private static final Path DEFAULT_FACE_RECOGNITION_SCRIPT = Path.of(System.getProperty("user.dir"), "src/main/python/net/lckx/video/face_recognize.py").toAbsolutePath().normalize();
    private static final Pattern GENERATED_PERSON_CANDIDATE = Pattern.compile("frame-\\d+-[0-9m]+s(?:-\\d+px)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern KNOWN_PERSON_FRAME_SUFFIX = Pattern.compile("[_-](?:\\d+[-_])?[0-9m]+s(?:-\\d+px)?$", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) {
        int exitCode = new DescribeVideo().run(args);
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
                ensureCommandAvailable("ffmpeg", "Install ffmpeg first, for example: brew install ffmpeg");
                addKnownPerson(options.peopleDir(), options.addPersonRequest());
                return 0;
            }
            validateVideoPath(options.videoPath());
            ensureCommandAvailable("ffmpeg", "Install ffmpeg first, for example: brew install ffmpeg");
            ensureCommandAvailable("ffprobe", "Install ffmpeg first, for example: brew install ffmpeg");
            options = askForSpeechTranscriptionIfNeeded(options);

            RunTimer timer = RunTimer.start();
            timer.log("Starting video analysis.");
            long ollamaStartedAt = System.nanoTime();
            timer.log("Checking Ollama connection...");
            OllamaClient ollama = new OllamaClient(options.ollamaHost(), options.model(), options.requestTimeout());
            ollama.ping();
            timer.log("Ollama connection ready in " + formatDuration(Duration.ofNanos(System.nanoTime() - ollamaStartedAt)) + ".");
            long peopleStartedAt = System.nanoTime();
            KnownPeople knownPeople = KnownPeople.load(options.peopleDir(), options.maxPersonReferences());
            if (!knownPeople.isEmpty()) {
                timer.log("Loaded known people in " + formatDuration(Duration.ofNanos(System.nanoTime() - peopleStartedAt))
                        + "; each frame request includes a comparison sheet.");
            }

            long durationStartedAt = System.nanoTime();
            double durationSeconds = readDurationSeconds(options.videoPath());
            timer.log("Read video duration in " + formatDuration(Duration.ofNanos(System.nanoTime() - durationStartedAt)) + ".");
            AnalysisPlan analysisPlan = createAnalysisPlan(durationSeconds, options);
            List<Double> sampleTimes = sampleTimes(
                    durationSeconds,
                    analysisPlan.frameCount(),
                    options.sampleEverySeconds(),
                    options.randomSamples(),
                    options.randomSeed() == null ? new Random() : new Random(options.randomSeed())
            );
            workDir = Files.createTempDirectory("video-description-");
            personRecognition = preparePersonRecognition(options, knownPeople, workDir, timer);

            System.out.println();
            System.out.println("Video: " + options.videoPath().toAbsolutePath());
            System.out.println("Duration: " + formatTimestamp(durationSeconds));
            System.out.println("Samples: " + sampleTimes.size() + " frames");
            if (options.randomSamples()) {
                System.out.println("Sample timestamps: randomized" + (options.randomSeed() == null ? "" : " with seed " + options.randomSeed()));
            }
            System.out.println("Frame width: " + analysisPlan.imageWidth() + " px");
            if (options.autoTune()) {
                System.out.println("Auto tuning: enabled");
                if (analysisPlan.adjusted()) {
                    System.out.println("Auto profile: " + analysisPlan.reason());
                }
            }
            System.out.println("Ollama timeout: " + options.requestTimeout().toMinutes() + " minutes");
            System.out.println("Model: " + options.model() + " via " + options.ollamaHost());
            if (!knownPeople.isEmpty()) {
                System.out.println("Known people: " + knownPeople.names());
                System.out.println("Known people recognition: " + personRecognition.description());
            } else if (options.maxPersonReferences() == 0) {
                System.out.println("Known people comparison: disabled");
            }
            if (options.savePersonCandidates()) {
                System.out.println("Person candidate pictures: enabled");
            }
            System.out.println("Speech transcription: " + (options.transcribeSpeech() ? "enabled" : "disabled"));
            System.out.println();

            List<FrameObservation> observations = new ArrayList<>();
            int currentImageWidth = analysisPlan.imageWidth();
            for (int i = 0; i < sampleTimes.size(); i++) {
                double seconds = sampleTimes.get(i);
                FrameAnalysisResult result = analyzeFrame(
                        options.videoPath(),
                        workDir,
                        i + 1,
                        sampleTimes.size(),
                        seconds,
                        currentImageWidth,
                        options.autoTune(),
                        ollama,
                        personRecognition,
                        timer
                );
                if (result != null) {
                    currentImageWidth = result.nextImageWidth();
                    observations.add(new FrameObservation(i + 1, seconds, result.framePath(), result.text()));
                }
            }

            if (observations.isEmpty()) {
                throw new IOException("No frames could be analyzed before Ollama timed out, even after automatic smaller-frame retries.");
            }

            List<Path> personCandidateImages = List.of();
            if (options.savePersonCandidates()) {
                long candidatesStartedAt = System.nanoTime();
                timer.log("Saving person candidate pictures...");
                personCandidateImages = savePersonCandidateImages(options.videoPath(), options.peopleDir(), observations);
                timer.log("Person candidate picture scan finished in "
                        + formatDuration(Duration.ofNanos(System.nanoTime() - candidatesStartedAt)) + ".");
                if (!personCandidateImages.isEmpty()) {
                    Path firstCandidateImage = personCandidateImages.get(0);
                    System.out.println();
                    System.out.println("Saved person candidate pictures to: " + firstCandidateImage.getParent());
                    System.out.println("Rename pictures to the person's name while keeping the frame location, for example: "
                            + exampleRenamedPersonPath(firstCandidateImage));
                    System.out.println("Future runs use renamed pictures in " + options.peopleDir() + " as known people.");
                }
            }

            String transcript = "";
            if (options.transcribeSpeech()) {
                Path audioPath = workDir.resolve("speech.wav");
                System.out.println();
                long audioStartedAt = System.nanoTime();
                timer.log("Extracting audio...");
                extractAudio(options.videoPath(), audioPath);
                timer.log("Extracted audio in " + formatDuration(Duration.ofNanos(System.nanoTime() - audioStartedAt)) + ".");

                SpeechTranscriber transcriber = SpeechTranscriber.resolve(
                        options.transcriber(),
                        options.speechModel(),
                        options.speechLanguage(),
                        options.speechTimeout()
                );
                long transcriptionStartedAt = System.nanoTime();
                timer.log("Transcribing speech with " + transcriber.displayName() + "...");
                transcript = transcriber.transcribe(audioPath, workDir);
                timer.log("Transcribed speech in "
                        + formatDuration(Duration.ofNanos(System.nanoTime() - transcriptionStartedAt)) + ".");
            }

            System.out.println();
            long summaryStartedAt = System.nanoTime();
            timer.log("Building video summary...");
            String summary;
            try {
                summary = ollama.summarizeVideo(options.videoPath().getFileName().toString(), durationSeconds, observations, transcript);
                timer.log("Built video summary in " + formatDuration(Duration.ofNanos(System.nanoTime() - summaryStartedAt)) + ".");
            } catch (OllamaRequestTimeoutException e) {
                timer.log("Summary generation timed out after "
                        + formatDuration(Duration.ofNanos(System.nanoTime() - summaryStartedAt))
                        + "; printing the collected frame observations instead.");
                summary = fallbackSummary(durationSeconds, observations, transcript);
            }

            System.out.println();
            System.out.println("=== Video description ===");
            System.out.println(removeAbsentContent(summary, options.transcribeSpeech() && !transcript.isBlank()).strip());

            if (options.showFrameDetails()) {
                System.out.println();
                System.out.println("=== Frame observations ===");
                for (FrameObservation observation : observations) {
                    System.out.printf("[%s] %s%n", formatTimestamp(observation.seconds()), observation.text().strip());
                }
            }

            if (options.transcribeSpeech() && !transcript.isBlank()) {
                System.out.println();
                System.out.println("=== Speech transcript ===");
                System.out.println(transcript.strip());
            }

            if (options.keepFrames()) {
                System.out.println();
                System.out.println("Sampled frames kept in: " + workDir.toAbsolutePath());
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
            System.err.println("Interrupted while describing video.");
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
            if (workDir != null && (options == null || !options.keepFrames())) {
                try {
                    deleteRecursively(workDir);
                } catch (IOException e) {
                    System.err.println("Warning: could not clean up temporary frames in " + workDir + ": " + e.getMessage());
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
        int frameCount = DEFAULT_FRAME_COUNT;
        Integer sampleEverySeconds = null;
        boolean randomSamples = false;
        Long randomSeed = null;
        int imageWidth = DEFAULT_IMAGE_WIDTH;
        Duration requestTimeout = Duration.ofMinutes(DEFAULT_TIMEOUT_MINUTES);
        boolean frameCountExplicit = false;
        boolean imageWidthExplicit = false;
        boolean autoTune = true;
        boolean transcribeSpeech = true;
        boolean transcribeSpeechExplicit = false;
        String transcriber = DEFAULT_TRANSCRIBER;
        String speechModel = DEFAULT_SPEECH_MODEL;
        String speechLanguage = DEFAULT_SPEECH_LANGUAGE;
        Duration speechTimeout = Duration.ofMinutes(DEFAULT_SPEECH_TIMEOUT_MINUTES);
        Path peopleDir = DEFAULT_PEOPLE_DIR;
        boolean savePersonCandidates = true;
        int maxPersonReferences = DEFAULT_MAX_PERSON_REFERENCES;
        String personRecognition = DEFAULT_PERSON_RECOGNITION;
        String faceRecognitionPython = DEFAULT_FACE_RECOGNITION_PYTHON;
        Path faceRecognitionScript = DEFAULT_FACE_RECOGNITION_SCRIPT;
        double faceRecognitionTolerance = DEFAULT_FACE_RECOGNITION_TOLERANCE;
        AddPersonRequest addPersonRequest = null;
        boolean keepFrames = false;
        boolean showFrameDetails = false;
        Path videoPath = null;

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
            } else if (arg.equals("--frames")) {
                frameCount = parseFrameCount(requireValue(args, ++i, "--frames"));
                frameCountExplicit = true;
            } else if (arg.startsWith("--frames=")) {
                frameCount = parseFrameCount(valueAfterEquals(arg, "--frames"));
                frameCountExplicit = true;
            } else if (arg.equals("--sample-every-seconds")) {
                sampleEverySeconds = parseBoundedInt(requireValue(args, ++i, "--sample-every-seconds"), "--sample-every-seconds", 1, 3_600);
            } else if (arg.startsWith("--sample-every-seconds=")) {
                sampleEverySeconds = parseBoundedInt(valueAfterEquals(arg, "--sample-every-seconds"), "--sample-every-seconds", 1, 3_600);
            } else if (arg.equals("--random-samples")) {
                randomSamples = true;
            } else if (arg.equals("--random-seed")) {
                randomSeed = parseLong(requireValue(args, ++i, "--random-seed"), "--random-seed");
                randomSamples = true;
            } else if (arg.startsWith("--random-seed=")) {
                randomSeed = parseLong(valueAfterEquals(arg, "--random-seed"), "--random-seed");
                randomSamples = true;
            } else if (arg.equals("--image-width")) {
                imageWidth = parseBoundedInt(requireValue(args, ++i, "--image-width"), "--image-width", MIN_IMAGE_WIDTH, MAX_IMAGE_WIDTH);
                imageWidthExplicit = true;
            } else if (arg.startsWith("--image-width=")) {
                imageWidth = parseBoundedInt(valueAfterEquals(arg, "--image-width"), "--image-width", MIN_IMAGE_WIDTH, MAX_IMAGE_WIDTH);
                imageWidthExplicit = true;
            } else if (arg.equals("--timeout-minutes")) {
                requestTimeout = Duration.ofMinutes(parseBoundedInt(requireValue(args, ++i, "--timeout-minutes"), "--timeout-minutes", 1, MAX_TIMEOUT_MINUTES));
            } else if (arg.startsWith("--timeout-minutes=")) {
                requestTimeout = Duration.ofMinutes(parseBoundedInt(valueAfterEquals(arg, "--timeout-minutes"), "--timeout-minutes", 1, MAX_TIMEOUT_MINUTES));
            } else if (arg.equals("--no-auto-tune")) {
                autoTune = false;
            } else if (arg.equals("--transcribe")) {
                transcribeSpeech = true;
                transcribeSpeechExplicit = true;
            } else if (arg.equals("--no-transcribe")) {
                transcribeSpeech = false;
                transcribeSpeechExplicit = true;
            } else if (arg.equals("--transcriber")) {
                transcriber = requireValue(args, ++i, "--transcriber");
                transcribeSpeech = true;
                transcribeSpeechExplicit = true;
            } else if (arg.startsWith("--transcriber=")) {
                transcriber = valueAfterEquals(arg, "--transcriber");
                transcribeSpeech = true;
                transcribeSpeechExplicit = true;
            } else if (arg.equals("--speech-model")) {
                speechModel = requireValue(args, ++i, "--speech-model");
                transcribeSpeech = true;
                transcribeSpeechExplicit = true;
            } else if (arg.startsWith("--speech-model=")) {
                speechModel = valueAfterEquals(arg, "--speech-model");
                transcribeSpeech = true;
                transcribeSpeechExplicit = true;
            } else if (arg.equals("--speech-language")) {
                speechLanguage = requireValue(args, ++i, "--speech-language");
                transcribeSpeech = true;
                transcribeSpeechExplicit = true;
            } else if (arg.startsWith("--speech-language=")) {
                speechLanguage = valueAfterEquals(arg, "--speech-language");
                transcribeSpeech = true;
                transcribeSpeechExplicit = true;
            } else if (arg.equals("--speech-timeout-minutes")) {
                speechTimeout = Duration.ofMinutes(parseBoundedInt(requireValue(args, ++i, "--speech-timeout-minutes"), "--speech-timeout-minutes", 1, MAX_SPEECH_TIMEOUT_MINUTES));
                transcribeSpeech = true;
                transcribeSpeechExplicit = true;
            } else if (arg.startsWith("--speech-timeout-minutes=")) {
                speechTimeout = Duration.ofMinutes(parseBoundedInt(valueAfterEquals(arg, "--speech-timeout-minutes"), "--speech-timeout-minutes", 1, MAX_SPEECH_TIMEOUT_MINUTES));
                transcribeSpeech = true;
                transcribeSpeechExplicit = true;
            } else if (arg.equals("--people-dir")) {
                peopleDir = expandHomePath(requireValue(args, ++i, "--people-dir"));
            } else if (arg.startsWith("--people-dir=")) {
                peopleDir = expandHomePath(valueAfterEquals(arg, "--people-dir"));
            } else if (arg.equals("--save-person-candidates")) {
                savePersonCandidates = true;
            } else if (arg.equals("--no-save-person-candidates")) {
                savePersonCandidates = false;
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
                Path imagePath = expandHomePath(requireValue(args, ++i, "--add-person image"));
                addPersonRequest = new AddPersonRequest(name, imagePath);
            } else if (arg.equals("--keep-frames")) {
                keepFrames = true;
            } else if (arg.equals("--details")) {
                showFrameDetails = true;
            } else if (arg.startsWith("-")) {
                throw new UsageException("Unknown option: " + arg);
            } else if (videoPath == null) {
                videoPath = Path.of(arg);
            } else {
                throw new UsageException("Only one video file can be described at a time.");
            }
        }

        if (addPersonRequest != null && videoPath != null) {
            throw new UsageException("--add-person is a standalone command and cannot be combined with a video file.");
        }
        if (addPersonRequest == null && videoPath == null) {
            throw new UsageException("Missing video file.");
        }
        if (frameCountExplicit && sampleEverySeconds != null) {
            throw new UsageException("Use either --frames or --sample-every-seconds, not both.");
        }
        if (model.isBlank()) {
            throw new UsageException("Model name cannot be empty.");
        }
        transcriber = transcriber.trim();
        speechModel = speechModel.trim();
        speechLanguage = speechLanguage.trim();
        if (!SUPPORTED_TRANSCRIBERS.contains(transcriber)) {
            throw new UsageException("--transcriber must be one of: " + String.join(", ", SUPPORTED_TRANSCRIBERS));
        }
        if (!SUPPORTED_PERSON_RECOGNITION.contains(personRecognition)) {
            throw new UsageException("--person-recognition must be one of: " + String.join(", ", SUPPORTED_PERSON_RECOGNITION));
        }
        if (speechModel.isBlank()) {
            throw new UsageException("--speech-model cannot be empty.");
        }
        if (speechLanguage.isBlank()) {
            throw new UsageException("--speech-language cannot be empty.");
        }
        if (peopleDir.toString().isBlank()) {
            throw new UsageException("--people-dir cannot be empty.");
        }

        return new Options(videoPath, model, host, frameCount, imageWidth, requestTimeout, frameCountExplicit,
                imageWidthExplicit, autoTune, transcribeSpeech, transcribeSpeechExplicit, transcriber, speechModel, speechLanguage,
                speechTimeout, peopleDir, savePersonCandidates, maxPersonReferences, addPersonRequest, sampleEverySeconds,
                personRecognition, faceRecognitionPython, faceRecognitionScript, faceRecognitionTolerance,
                randomSamples, randomSeed, keepFrames, showFrameDetails);
    }

    private static Options askForSpeechTranscriptionIfNeeded(Options options) throws IOException {
        if (options.transcribeSpeechExplicit()) {
            return options;
        }

        Boolean answer = askYesNo("Speech transcription is enabled by default. Transcribe speech for this video? [Y/n] ", true);
        boolean transcribeSpeech = answer == null ? true : answer;
        return options.withTranscribeSpeech(transcribeSpeech);
    }

    private static Boolean askYesNo(String question, boolean defaultValue) throws IOException {
        while (true) {
            String answer = readPromptLine(question);
            if (answer == null) {
                return defaultValue;
            }
            Boolean parsed = parseYesNoAnswer(answer, defaultValue);
            if (parsed != null) {
                return parsed;
            }
            System.out.println("Please answer y or n. Press Enter for yes.");
        }
    }

    private static String readPromptLine(String question) throws IOException {
        var console = System.console();
        if (console != null) {
            return console.readLine(question);
        }
        if (System.in.available() > 0) {
            System.out.print(question);
            System.out.flush();
            String answer = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            System.out.println();
            return answer;
        }
        return null;
    }

    static Boolean parseYesNoAnswer(String answer, boolean defaultValue) {
        String normalized = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return defaultValue;
        }
        if (normalized.equals("y") || normalized.equals("yes")) {
            return true;
        }
        if (normalized.equals("n") || normalized.equals("no")) {
            return false;
        }
        return null;
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

    private static int parseFrameCount(String value) {
        return parseBoundedInt(value, "--frames", 1, MAX_FRAME_COUNT);
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

    private static long parseLong(String value, String option) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UsageException(option + " must be a whole number.");
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

    private static void validateVideoPath(Path videoPath) {
        if (!Files.exists(videoPath)) {
            throw new UsageException("Video file not found: " + videoPath);
        }
        if (!Files.isRegularFile(videoPath)) {
            throw new UsageException("Not a regular video file: " + videoPath);
        }
        if (!Files.isReadable(videoPath)) {
            throw new UsageException("Video file is not readable: " + videoPath);
        }
    }

    private static void ensureCommandAvailable(String command, String installHint) throws IOException, InterruptedException {
        CommandResult result = runCommand(List.of(command, "-version"));
        if (result.exitCode() != 0) {
            throw new IOException(command + " is not available. " + installHint);
        }
    }

    static double readDurationSeconds(Path videoPath) throws IOException, InterruptedException {
        CommandResult result = runCommand(List.of(
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                videoPath.toString()
        ));
        if (result.exitCode() != 0) {
            throw new IOException("Could not read video duration with ffprobe: " + truncate(result.output(), 600));
        }

        String value = result.output().trim().lines().findFirst().orElse("");
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || seconds <= 0) {
                throw new IOException("ffprobe returned an invalid duration: " + value);
            }
            return seconds;
        } catch (NumberFormatException e) {
            throw new IOException("ffprobe returned an invalid duration: " + value);
        }
    }

    static List<Double> sampleTimes(double durationSeconds, int frameCount) {
        return sampleTimes(durationSeconds, frameCount, null);
    }

    static List<Double> sampleTimes(double durationSeconds, int frameCount, Integer sampleEverySeconds) {
        return sampleTimes(durationSeconds, frameCount, sampleEverySeconds, false, new Random());
    }

    static List<Double> sampleTimes(double durationSeconds, int frameCount, Integer sampleEverySeconds, boolean randomSamples, Random random) {
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        if (randomSamples && random == null) {
            throw new IllegalArgumentException("random must be provided when randomSamples is true");
        }
        if (sampleEverySeconds != null) {
            return randomSamples
                    ? sampleTimesEveryRandom(durationSeconds, sampleEverySeconds, random)
                    : sampleTimesEvery(durationSeconds, sampleEverySeconds);
        }
        if (frameCount < 1) {
            throw new IllegalArgumentException("frameCount must be positive");
        }
        if (randomSamples) {
            return sampleTimesRandom(durationSeconds, frameCount, random);
        }

        List<Double> times = new ArrayList<>();
        if (frameCount == 1) {
            times.add(durationSeconds / 2.0);
            return times;
        }

        double step = durationSeconds / (frameCount + 1.0);
        double latestSafeTime = Math.max(0, durationSeconds - 0.1);
        for (int i = 1; i <= frameCount; i++) {
            double seconds = Math.min(latestSafeTime, step * i);
            times.add(Math.max(0, seconds));
        }
        return times;
    }

    static List<Double> sampleTimesRandom(double durationSeconds, int frameCount, Random random) {
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        if (frameCount < 1) {
            throw new IllegalArgumentException("frameCount must be positive");
        }
        if (random == null) {
            throw new IllegalArgumentException("random must be provided");
        }

        double latestSafeTime = Math.max(0, durationSeconds - 0.1);
        if (frameCount == 1) {
            return List.of(randomSecond(random, 0, latestSafeTime));
        }

        List<Double> times = new ArrayList<>();
        double segment = latestSafeTime / frameCount;
        for (int i = 0; i < frameCount; i++) {
            double start = segment * i;
            double end = i == frameCount - 1 ? latestSafeTime : segment * (i + 1);
            times.add(randomSecond(random, start, end));
        }
        times.sort(Double::compareTo);
        return times;
    }

    static List<Double> sampleTimesEvery(double durationSeconds, int intervalSeconds) {
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        if (intervalSeconds < 1) {
            throw new IllegalArgumentException("intervalSeconds must be positive");
        }

        double latestSafeTime = Math.max(0, durationSeconds - 0.1);
        if (durationSeconds <= intervalSeconds) {
            return List.of(durationSeconds / 2.0);
        }

        List<Double> times = new ArrayList<>();
        for (double seconds = intervalSeconds; seconds <= latestSafeTime; seconds += intervalSeconds) {
            times.add(seconds);
        }
        if (times.isEmpty()) {
            times.add(durationSeconds / 2.0);
        }
        return times;
    }

    static List<Double> sampleTimesEveryRandom(double durationSeconds, int intervalSeconds, Random random) {
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        if (intervalSeconds < 1) {
            throw new IllegalArgumentException("intervalSeconds must be positive");
        }
        if (random == null) {
            throw new IllegalArgumentException("random must be provided");
        }

        double latestSafeTime = Math.max(0, durationSeconds - 0.1);
        if (durationSeconds <= intervalSeconds) {
            return List.of(randomSecond(random, 0, latestSafeTime));
        }

        int sampleCount = sampleTimesEvery(durationSeconds, intervalSeconds).size();
        List<Double> times = new ArrayList<>();
        for (int i = 0; i < sampleCount; i++) {
            double start = i * (double) intervalSeconds;
            double end = Math.min(latestSafeTime, (i + 1.0) * intervalSeconds);
            times.add(randomSecond(random, start, end));
        }
        times.sort(Double::compareTo);
        return times;
    }

    private static double randomSecond(Random random, double start, double end) {
        double safeStart = Math.max(0, start);
        double safeEnd = Math.max(safeStart, end);
        if (safeEnd <= safeStart) {
            return safeStart;
        }
        return safeStart + (random.nextDouble() * (safeEnd - safeStart));
    }

    static AnalysisPlan createAnalysisPlan(double durationSeconds, Options options) {
        int frameCount = options.frameCount();
        int imageWidth = options.imageWidth();

        if (options.sampleEverySeconds() != null) {
            frameCount = sampleTimesEvery(durationSeconds, options.sampleEverySeconds()).size();
            if (frameCount > MAX_INTERVAL_SAMPLE_COUNT) {
                throw new UsageException("--sample-every-seconds would analyze " + frameCount
                        + " frames. Increase the interval or use --frames. Max interval samples: " + MAX_INTERVAL_SAMPLE_COUNT + ".");
            }
            if (options.autoTune() && !options.imageWidthExplicit()) {
                imageWidth = recommendedImageWidth(durationSeconds);
            }
            String reason = "sample interval selected " + frameCount + " frames every "
                    + options.sampleEverySeconds() + " seconds at " + imageWidth + " px";
            return new AnalysisPlan(frameCount, imageWidth, true, reason);
        }

        if (!options.autoTune()) {
            return new AnalysisPlan(frameCount, imageWidth, false, "disabled");
        }

        if (!options.frameCountExplicit()) {
            frameCount = recommendedFrameCount(durationSeconds);
        }
        if (!options.imageWidthExplicit()) {
            imageWidth = recommendedImageWidth(durationSeconds);
        }

        boolean adjusted = frameCount != options.frameCount() || imageWidth != options.imageWidth();
        String reason = adjusted
                ? "duration-aware defaults selected " + frameCount + " frames at " + imageWidth + " px"
                : "using requested frame count and image width";
        return new AnalysisPlan(frameCount, imageWidth, adjusted, reason);
    }

    static int recommendedFrameCount(double durationSeconds) {
        if (durationSeconds <= 60) {
            return 4;
        }
        if (durationSeconds <= 120) {
            return 6;
        }
        if (durationSeconds <= 600) {
            return 8;
        }
        if (durationSeconds <= 1_800) {
            return 12;
        }
        return 16;
    }

    static int recommendedImageWidth(double durationSeconds) {
        if (durationSeconds <= 120) {
            return 384;
        }
        return 256;
    }

    private static FrameAnalysisResult analyzeFrame(Path videoPath, Path workDir, int frameIndex, int frameCount,
                                                    double seconds, int imageWidth, boolean autoTune,
                                                    OllamaClient ollama, PersonRecognitionPlan personRecognition,
                                                    RunTimer timer) throws IOException, InterruptedException {
        int width = imageWidth;

        while (true) {
            Path framePath = workDir.resolve("frame-%02d-%dpx.jpg".formatted(frameIndex, width));
            timer.log("Extracting frame %d/%d at %s (%d px)...".formatted(frameIndex, frameCount, formatTimestamp(seconds), width));
            long extractionStartedAt = System.nanoTime();
            extractFrame(videoPath, seconds, framePath, width);
            timer.log("Extracted frame %d/%d in %s.".formatted(
                    frameIndex,
                    frameCount,
                    formatDuration(Duration.ofNanos(System.nanoTime() - extractionStartedAt))
            ));

            timer.log("Analyzing frame %d/%d...".formatted(frameIndex, frameCount));
            long startedAt = System.nanoTime();
            try {
                FaceRecognitionResult faceRecognition = FaceRecognitionResult.empty();
                if (personRecognition.faceRecognizer() != null) {
                    long faceStartedAt = System.nanoTime();
                    faceRecognition = personRecognition.faceRecognizer().recognize(framePath);
                    timer.log("Local face recognition for frame %d/%d finished in %s%s.".formatted(
                            frameIndex,
                            frameCount,
                            formatDuration(Duration.ofNanos(System.nanoTime() - faceStartedAt)),
                            faceRecognition.hasMatches() ? "; matched " + faceRecognition.namesText() : ""
                    ));
                }

                String observation = ollama.describeFrame(framePath, seconds, personRecognition.ollamaKnownPeople(), faceRecognition);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
                timer.log("Analyzed frame %d/%d in %s.".formatted(frameIndex, frameCount, formatDuration(elapsed)));
                int nextWidth = width;
                if (autoTune && elapsed.compareTo(SLOW_FRAME_THRESHOLD) > 0) {
                    int smallerWidth = nextAdaptiveImageWidth(width);
                    if (smallerWidth < width) {
                        timer.log("Auto tuning: frame analysis took " + formatDuration(elapsed)
                                + "; using " + smallerWidth + " px for the next frames.");
                        nextWidth = smallerWidth;
                    }
                }
                return new FrameAnalysisResult(observation, framePath, nextWidth);
            } catch (OllamaRequestTimeoutException e) {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
                int smallerWidth = nextAdaptiveImageWidth(width);
                if (autoTune && smallerWidth < width) {
                    timer.log("Auto tuning: frame " + frameIndex + " timed out after " + formatDuration(elapsed)
                            + " at " + width + " px; retrying at " + smallerWidth + " px.");
                    width = smallerWidth;
                    continue;
                }

                if (autoTune) {
                    timer.log("Auto tuning: skipping frame " + frameIndex + " after timeout at minimum image width. Continuing with the other frames.");
                    return null;
                }

                throw e;
            }
        }
    }

    static int nextAdaptiveImageWidth(int imageWidth) {
        if (imageWidth > 256) {
            return 256;
        }
        if (imageWidth > AUTO_MIN_IMAGE_WIDTH) {
            return AUTO_MIN_IMAGE_WIDTH;
        }
        return imageWidth;
    }

    private static String fallbackSummary(double durationSeconds, List<FrameObservation> observations, String transcript) {
        StringBuilder summary = new StringBuilder();
        summary.append("Summary:\n");
        summary.append("Based on sampled frames from a ").append(formatTimestamp(durationSeconds)).append(" video.\n\n");
        summary.append("Frame observations:\n");
        for (FrameObservation observation : observations) {
            summary.append("- ")
                    .append(formatTimestamp(observation.seconds()))
                    .append(": ")
                    .append(observation.text().strip())
                    .append('\n');
        }
        if (transcript != null && !transcript.isBlank()) {
            summary.append("\nSpeech:\n").append(transcript.strip()).append('\n');
        }
        return summary.toString();
    }

    private static List<Path> savePersonCandidateImages(Path videoPath, Path peopleDir, List<FrameObservation> observations) throws IOException {
        List<FrameObservation> personObservations = observations.stream()
                .filter(observation -> looksLikePersonObservation(observation.text()))
                .toList();
        if (personObservations.isEmpty()) {
            return List.of();
        }

        Path outputDir = peopleDir.resolve(sanitizeFilename(stripExtension(videoPath.getFileName().toString())));
        Files.createDirectories(outputDir);

        List<Path> saved = new ArrayList<>();
        for (FrameObservation observation : personObservations) {
            Path destination = outputDir.resolve("frame-%02d-%s.jpg".formatted(
                    observation.index(),
                    formatTimestamp(observation.seconds()).replace(":", "m") + "s"
            ));
            Files.copy(observation.framePath(), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            saved.add(destination);
        }
        return saved;
    }

    private static Path exampleRenamedPersonPath(Path candidateImage) {
        String filename = candidateImage.getFileName().toString().replaceFirst("^frame-", "");
        return candidateImage.resolveSibling("Miranda-" + filename);
    }

    static boolean looksLikePersonObservation(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("person")
                || normalized.contains("people")
                || normalized.contains("child")
                || normalized.contains("girl")
                || normalized.contains("boy")
                || normalized.contains("woman")
                || normalized.contains("women")
                || normalized.contains("man")
                || normalized.contains("men")
                || normalized.contains("adult")
                || normalized.contains("face")
                || normalized.contains("portrait");
    }

    private static void addKnownPerson(Path peopleDir, AddPersonRequest request) throws IOException, InterruptedException {
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
        System.out.println("Future video descriptions will use this local reference when comparing visible people.");
    }

    private static void normalizeReferenceImage(Path source, Path destination) throws IOException, InterruptedException {
        CommandResult result = runCommand(List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-i", source.toString(),
                "-vf", "scale=" + PERSON_REFERENCE_WIDTH + ":-2",
                "-q:v", "3",
                destination.toString()
        ));
        if (result.exitCode() != 0 || !Files.exists(destination) || Files.size(destination) == 0) {
            throw new IOException("Could not create person reference picture: " + truncate(result.output(), 600));
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

    static void extractFrame(Path videoPath, double seconds, Path outputPath, int imageWidth) throws IOException, InterruptedException {
        CommandResult result = runCommand(List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", seconds),
                "-i", videoPath.toString(),
                "-frames:v", "1",
                "-vf", "scale=" + imageWidth + ":-2",
                "-q:v", "3",
                outputPath.toString()
        ));

        if (result.exitCode() != 0 || !Files.exists(outputPath) || Files.size(outputPath) == 0) {
            throw new IOException("Could not extract frame at " + formatTimestamp(seconds) + ": " + truncate(result.output(), 600));
        }
    }

    static void extractAudio(Path videoPath, Path outputPath) throws IOException, InterruptedException {
        CommandResult result = runCommand(List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-i", videoPath.toString(),
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "pcm_s16le",
                outputPath.toString()
        ));

        if (result.exitCode() != 0 || !Files.exists(outputPath) || Files.size(outputPath) == 0) {
            throw new IOException("Could not extract audio for transcription: " + truncate(result.output(), 600));
        }
    }

    private static CommandResult runCommand(List<String> command) throws IOException, InterruptedException {
        return runCommand(command, null);
    }

    private static CommandResult runCommand(List<String> command, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        try {
            boolean finished;
            if (timeout == null) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            if (!finished) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                throw new IOException("Command timed out after " + timeout.toMinutes() + " minutes: " + command.get(0));
            }

            String output = outputFuture.join();
            return new CommandResult(process.exitValue(), output);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        } catch (CompletionException e) {
            if (e.getCause() instanceof UncheckedIOException uncheckedIOException) {
                throw uncheckedIOException.getCause();
            }
            throw e;
        }
    }

    static String formatTimestamp(double seconds) {
        long totalSeconds = Math.max(0, Math.round(seconds));
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long secs = totalSeconds % 60;
        if (hours > 0) {
            return "%02d:%02d:%02d".formatted(hours, minutes, secs);
        }
        return "%02d:%02d".formatted(minutes, secs);
    }

    static String formatDuration(Duration duration) {
        long millis = Math.max(0, duration.toMillis());
        if (millis < 1_000) {
            return millis + "ms";
        }

        long seconds = millis / 1_000;
        if (seconds < 60) {
            long tenths = (millis % 1_000) / 100;
            return tenths == 0 ? seconds + "s" : seconds + "." + tenths + "s";
        }

        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        if (minutes > 0) {
            return minutes + "m " + remainingSeconds + "s";
        }
        return remainingSeconds + "s";
    }

    static String formatElapsedTimestamp(Duration duration) {
        long millis = Math.max(0, duration.toMillis());
        long hours = millis / 3_600_000;
        long minutes = (millis % 3_600_000) / 60_000;
        long seconds = (millis % 60_000) / 1_000;
        long remainingMillis = millis % 1_000;
        if (hours > 0) {
            return "+%02d:%02d:%02d.%03d".formatted(hours, minutes, seconds, remainingMillis);
        }
        return "+%02d:%02d.%03d".formatted(minutes, seconds, remainingMillis);
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java <video-file> [options]

                Options:
                  --model <name>       Ollama vision model to use. Default: qwen2.5vl:7b
                  --host <url>         Ollama host. Default: http://localhost:11434
                  --frames <number>    Number of frames to sample. Auto-tuned by duration unless provided, max: 50
                  --sample-every-seconds <n>
                                        Sample one frame every n seconds instead of using --frames
                  --random-samples     Choose random timestamps instead of the same evenly spaced samples
                  --random-seed <n>    Use repeatable random timestamps; implies --random-samples
                  --image-width <px>   Width of sampled frame images. Auto-tuned by duration unless provided
                  --timeout-minutes <n> Ollama request timeout. Default: 15, max: 120
                  --no-auto-tune       Disable duration-aware defaults and timeout retries
                  --transcribe         Skip the prompt and transcribe speech with a local Whisper command
                  --no-transcribe      Skip the prompt and describe sampled video frames without speech transcription
                  --transcriber <name> Speech transcriber: auto, whisper, whisper-cli, whisper-cpp
                  --speech-model <name-or-path>
                                       Whisper model name for 'whisper', or ggml model path for whisper.cpp
                  --speech-language <code>
                                       Spoken language code, e.g. auto, en, nl. Default: auto
                  --speech-timeout-minutes <n>
                                       Speech transcription timeout. Default: 30, max: 240
                  --people-dir <path>  Known people library. Default: ./video-people
                  --add-person <name> <image>
                                       Add a reference picture for a known person, then exit
                  --max-person-refs <n>
                                        Max known-person reference pictures sent to Ollama. Default: 8
                  --no-known-people    Do not compare frames with known people; candidate pictures are still saved
                  --person-recognition <mode>
                                        Known-person method: auto, face, ollama, off. Default: auto
                  --face-recognition-python <path>
                                        Python executable for local face_recognition. Default: python3
                  --face-recognition-script <path>
                                        Helper script path. Default: src/main/python/net/lckx/video/face_recognize.py
                  --face-recognition-tolerance <n>
                                        Local face match threshold. Default: 0.6; lower is stricter
                  --save-person-candidates
                                        Save sampled frames that appear to contain people. Enabled by default
                  --no-save-person-candidates
                                       Do not save candidate person pictures
                  --details            Print every sampled-frame observation after the final summary
                  --keep-frames        Keep extracted sample frames instead of deleting them
                  --help               Show this help

                Environment variables:
                  OLLAMA_VISION_MODEL  Overrides the default model
                  OLLAMA_HOST          Overrides the default Ollama host

                Example:
                  java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java ~/Movies/holiday.mp4 --frames 12 --details
                """);
    }

    private static void printSetupHelp() {
        System.err.println("""
                Setup:
                  brew install ffmpeg
                  brew install ollama
                  ollama pull qwen2.5vl:7b
                  ollama serve

                Speech transcription setup:
                  pipx install openai-whisper
                  # or: brew install whisper-cpp and pass --speech-model /path/to/ggml-model.bin

                Optional local face recognition setup:
                  python3 -m venv ~/.venvs/video-face-recognition
                  ~/.venvs/video-face-recognition/bin/pip install "setuptools<81" face_recognition
                  # then pass --face-recognition-python ~/.venvs/video-face-recognition/bin/python

                This tool describes visible video content from sampled frames. When no speech
                option is passed, it asks whether to transcribe speech and defaults to yes.
                Use --transcribe or --no-transcribe to skip that prompt.

                Known people workflow:
                  1. Run a video. Candidate pictures are saved under ./video-people/<video-name>/
                  2. Rename candidate pictures to the person's name, for example Miranda-01-01m26s.jpg
                  3. Future runs compare visible people against renamed images and use names when confident.

                Frame analysis uses duration-aware defaults and automatically retries timed-out
                frames at smaller image sizes unless --no-auto-tune is used.
                """);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    static String removeAbsentContent(String summary) {
        return removeAbsentContent(summary, false);
    }

    static String removeAbsentContent(String summary, boolean transcriptPresent) {
        List<String> lines = summary.lines().toList();
        StringBuilder cleaned = new StringBuilder();
        String heading = null;
        List<String> content = new ArrayList<>();

        for (String line : lines) {
            if (isSummaryHeading(line)) {
                appendSummaryBlock(cleaned, heading, content);
                heading = line.strip();
                content = new ArrayList<>();
            } else if (!describesAbsentContent(line, transcriptPresent)) {
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
        return List.of("summary", "people", "activities", "places/scenes", "notable objects", "speech", "tags", "confidence")
                .contains(normalized);
    }

    private static boolean describesAbsentContent(String line, boolean transcriptPresent) {
        String normalized = line.strip().toLowerCase(Locale.ROOT);
        boolean unsupportedTranscript = !transcriptPresent && (normalized.contains("transcript")
                || normalized.contains("speech transcript")
                || normalized.contains("the person says")
                || normalized.contains("[name]")
                || normalized.contains("[topic]")
                || normalized.contains("[verb]")
                || normalized.contains("[object/visual aid]"));
        return normalized.contains("not visible")
                || normalized.contains("not explicitly visible")
                || normalized.contains("not observed")
                || normalized.contains("not seen")
                || normalized.contains("not heard")
                || normalized.contains("not mentioned")
                || normalized.contains("not available")
                || normalized.contains("unavailable")
                || normalized.contains("not provided")
                || normalized.contains("no speech")
                || normalized.contains("no audio")
                || normalized.contains("transcript is not available")
                || normalized.contains("transcript not available")
                || normalized.contains("none explicitly")
                || normalized.contains("none mentioned")
                || normalized.contains("none visible")
                || normalized.contains(": none")
                || unsupportedTranscript
                || normalized.matches("[-* ]*(none|unknown|unclear|n/a)\\.?")
                || normalized.matches("[-* ]*(speech: )?(none|unknown|unclear|n/a)\\.?");
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

    record Options(Path videoPath, String model, URI ollamaHost, int frameCount, int imageWidth, Duration requestTimeout,
                   boolean frameCountExplicit, boolean imageWidthExplicit, boolean autoTune,
                   boolean transcribeSpeech, boolean transcribeSpeechExplicit, String transcriber, String speechModel, String speechLanguage, Duration speechTimeout,
                   Path peopleDir, boolean savePersonCandidates, int maxPersonReferences, AddPersonRequest addPersonRequest, Integer sampleEverySeconds,
                   String personRecognition, String faceRecognitionPython, Path faceRecognitionScript, double faceRecognitionTolerance,
                   boolean randomSamples, Long randomSeed, boolean keepFrames, boolean showFrameDetails) {
        Options withTranscribeSpeech(boolean value) {
            return new Options(videoPath, model, ollamaHost, frameCount, imageWidth, requestTimeout, frameCountExplicit,
                    imageWidthExplicit, autoTune, value, true, transcriber, speechModel, speechLanguage, speechTimeout,
                    peopleDir, savePersonCandidates, maxPersonReferences, addPersonRequest, sampleEverySeconds,
                    personRecognition, faceRecognitionPython, faceRecognitionScript, faceRecognitionTolerance,
                    randomSamples, randomSeed, keepFrames, showFrameDetails);
        }
    }

    record AddPersonRequest(String name, Path imagePath) {
    }

    record AnalysisPlan(int frameCount, int imageWidth, boolean adjusted, String reason) {
    }

    record FrameObservation(int index, double seconds, Path framePath, String text) {
    }

    record FrameAnalysisResult(String text, Path framePath, int nextImageWidth) {
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

    private record CommandResult(int exitCode, String output) {
    }

    static class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    static class HelpException extends UsageException {
        HelpException() {
            super("Video description tool");
        }
    }

    static class OllamaRequestTimeoutException extends IOException {
        OllamaRequestTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
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
            FaceRecognitionServer faceRecognizer = FaceRecognitionServer.start(options, knownPeople, workDir);
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
            for (int i = 0; i < references.size(); i++) {
                text.append("- ")
                        .append(references.get(i).name())
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

        static FaceRecognitionServer start(Options options, KnownPeople knownPeople, Path workDir) throws IOException {
            if (!Files.isRegularFile(options.faceRecognitionScript())) {
                throw new IOException("face recognition helper script not found: " + options.faceRecognitionScript());
            }

            Path referencesFile = writeFaceRecognitionReferences(workDir, knownPeople.references());
            ProcessBuilder processBuilder = new ProcessBuilder(
                    options.faceRecognitionPython(),
                    options.faceRecognitionScript().toString(),
                    "--server",
                    "--references", referencesFile.toString(),
                    "--tolerance", Double.toString(options.faceRecognitionTolerance())
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

        FaceRecognitionResult recognize(Path framePath) throws IOException {
            writer.write(framePath.toAbsolutePath().toString());
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

    static class SpeechTranscriber {
        private final String command;
        private final String model;
        private final String language;
        private final Duration timeout;

        private SpeechTranscriber(String command, String model, String language, Duration timeout) {
            this.command = command;
            this.model = model;
            this.language = language;
            this.timeout = timeout;
        }

        static SpeechTranscriber resolve(String requestedTranscriber, String model, String language, Duration timeout) throws IOException, InterruptedException {
            List<String> candidates = requestedTranscriber.equals("auto")
                    ? List.of("whisper", "whisper-cli", "whisper-cpp")
                    : List.of(requestedTranscriber);

            for (String candidate : candidates) {
                if (isCommandAvailable(candidate)) {
                    if (isWhisperCpp(candidate) && isDefaultSpeechModel(model)) {
                        throw new IOException(candidate + " requires --speech-model pointing to a whisper.cpp ggml model file.");
                    }
                    return new SpeechTranscriber(candidate, expandHome(model), language, timeout);
                }
            }

            throw new IOException("""
                    Speech transcription requested, but no supported local Whisper command was found.
                    Install one of:
                      pipx install openai-whisper
                      brew install whisper-cpp
                    Install a transcriber, or rerun with --no-transcribe for visual-only analysis.
                    For whisper.cpp also pass --speech-model /path/to/ggml-model.bin.
                    """);
        }

        String displayName() {
            return command + " (" + model + ")";
        }

        String transcribe(Path audioPath, Path workDir) throws IOException, InterruptedException {
            if (command.equals("whisper")) {
                return transcribeWithOpenAiWhisper(audioPath, workDir);
            }
            return transcribeWithWhisperCpp(audioPath, workDir);
        }

        private String transcribeWithOpenAiWhisper(Path audioPath, Path workDir) throws IOException, InterruptedException {
            Path outputDir = workDir.resolve("speech-transcript");
            Files.createDirectories(outputDir);

            List<String> commandLine = new ArrayList<>(List.of(
                    command,
                    audioPath.toString(),
                    "--model", model,
                    "--output_format", "txt",
                    "--output_dir", outputDir.toString(),
                    "--fp16", "False"
            ));
            if (!language.equals("auto")) {
                commandLine.add("--language");
                commandLine.add(language);
            }

            CommandResult result = runCommand(commandLine, timeout);
            if (result.exitCode() != 0) {
                throw new IOException("Speech transcription failed: " + truncate(result.output(), 1200));
            }

            Path transcriptPath = outputDir.resolve(stripExtension(audioPath.getFileName().toString()) + ".txt");
            return readTranscript(transcriptPath, outputDir, result.output());
        }

        private String transcribeWithWhisperCpp(Path audioPath, Path workDir) throws IOException, InterruptedException {
            Path outputPrefix = workDir.resolve("speech-transcript");
            List<String> commandLine = new ArrayList<>(List.of(
                    command,
                    "-m", model,
                    "-f", audioPath.toString(),
                    "-otxt",
                    "-of", outputPrefix.toString(),
                    "-l", language
            ));

            CommandResult result = runCommand(commandLine, timeout);
            if (result.exitCode() != 0) {
                throw new IOException("Speech transcription failed: " + truncate(result.output(), 1200));
            }

            return readTranscript(Path.of(outputPrefix + ".txt"), workDir, result.output());
        }

        private static boolean isCommandAvailable(String command) throws InterruptedException {
            try {
                runCommand(List.of(command, "--help"), Duration.ofSeconds(15));
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private static boolean isWhisperCpp(String command) {
            return command.equals("whisper-cli") || command.equals("whisper-cpp");
        }

        private static boolean isDefaultSpeechModel(String model) {
            return model.equals(DEFAULT_SPEECH_MODEL);
        }

        private static String expandHome(String value) {
            if (value.equals("~")) {
                return System.getProperty("user.home");
            }
            if (value.startsWith("~/")) {
                return System.getProperty("user.home") + value.substring(1);
            }
            return value;
        }

        private static String stripExtension(String filename) {
            int dot = filename.lastIndexOf('.');
            return dot > 0 ? filename.substring(0, dot) : filename;
        }

        private static String readTranscript(Path expectedPath, Path outputDir, String fallbackOutput) throws IOException {
            if (Files.exists(expectedPath)) {
                return Files.readString(expectedPath).strip();
            }

            try (Stream<Path> files = Files.list(outputDir)) {
                Path firstTxt = files
                        .filter(path -> path.getFileName().toString().endsWith(".txt"))
                        .findFirst()
                        .orElse(null);
                if (firstTxt != null) {
                    return Files.readString(firstTxt).strip();
                }
            }

            String fallback = fallbackOutput.strip();
            if (!fallback.isBlank()) {
                return fallback;
            }
            throw new IOException("Speech transcriber finished but did not produce transcript text.");
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

        String describeFrame(Path framePath, double seconds, KnownPeople knownPeople, FaceRecognitionResult faceRecognition) throws IOException, InterruptedException {
            List<String> images = new ArrayList<>();

            String knownPeopleInstructions = "";
            String frameScope = "Describe only what is visible in the image.";
            if (knownPeople != null && !knownPeople.isEmpty()) {
                byte[] comparisonImage = buildKnownPeopleComparisonImage(framePath, knownPeople.references());
                images.add(Base64.getEncoder().encodeToString(comparisonImage));
                frameScope = "Describe only what is visible in the VIDEO FRAME panel.";
                knownPeopleInstructions = """

                        The attached image is an analysis worksheet, not the video itself. The panel labelled VIDEO FRAME
                        is the only sampled video frame. Smaller panels labelled REFERENCE are known-person examples:
                        %s
                        Describe only what is visible in the VIDEO FRAME panel. If a visible person in the VIDEO FRAME
                        clearly matches a named REFERENCE panel, use that person's name. If you are not confident,
                        describe the person generically. Never describe the worksheet, labels, comparison sheet, reference
                        panels, or reference pictures as video content. Do not invent names.
                        """.formatted(knownPeople.promptReferenceList());
            } else {
                images.add(Base64.getEncoder().encodeToString(Files.readAllBytes(framePath)));
            }

            String faceRecognitionInstructions = "";
            if (faceRecognition != null && faceRecognition.hasMatches()) {
                faceRecognitionInstructions = """

                        Local face recognition matched visible face(s) in this frame to: %s.
                        Use these name(s) for matching visible people when it fits the image. Do not invent names
                        for other visible people.
                        """.formatted(faceRecognition.namesText());
            }

            String prompt = """
                    You are analyzing one sampled frame from a video at %s.
                    %s Include visible people and likely age group,
                    visible activities, place or scene, notable objects, animals, vehicles, text, mood, and concise tags.
                    Do not mention categories that are absent, unclear, or not visible. Do not invent details.
                    Keep it under 90 words.%s%s
                    """.formatted(formatTimestamp(seconds), frameScope, knownPeopleInstructions, faceRecognitionInstructions);
            return generate(prompt, images).strip();
        }

        private static byte[] buildKnownPeopleComparisonImage(Path framePath, List<KnownPersonReference> references) throws IOException {
            BufferedImage videoFrame = readImage(framePath, "video frame");
            List<KnownPersonImage> referenceImages = new ArrayList<>();
            for (KnownPersonReference reference : references) {
                referenceImages.add(new KnownPersonImage(reference.name(), readImage(reference.imagePath(), "known person reference " + reference.imagePath())));
            }

            int margin = 12;
            int labelHeight = 22;
            int maxVideoWidth = 320;
            int referenceSize = 96;
            int referenceColumns = Math.min(4, Math.max(1, referenceImages.size()));
            int videoWidth = Math.min(maxVideoWidth, videoFrame.getWidth());
            int videoHeight = Math.max(1, (int) Math.round(videoFrame.getHeight() * (videoWidth / (double) videoFrame.getWidth())));
            int referenceGridWidth = referenceColumns * referenceSize + (referenceColumns - 1) * margin;
            int canvasWidth = Math.max(videoWidth, referenceGridWidth) + margin * 2;
            int referenceRows = (int) Math.ceil(referenceImages.size() / (double) referenceColumns);
            int canvasHeight = margin + labelHeight + videoHeight + margin
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

                int videoX = (canvasWidth - videoWidth) / 2;
                int y = margin;
                drawLabel(graphics, "VIDEO FRAME", videoX, y, videoWidth);
                y += labelHeight;
                drawFittedImage(graphics, videoFrame, videoX, y, videoWidth, videoHeight);
                y += videoHeight + margin;

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

        String summarizeVideo(String videoName, double durationSeconds, List<FrameObservation> observations, String transcript) throws IOException, InterruptedException {
            StringBuilder frameText = new StringBuilder();
            for (FrameObservation observation : observations) {
                frameText.append("- ")
                        .append(formatTimestamp(observation.seconds()))
                        .append(": ")
                        .append(observation.text().strip())
                        .append('\n');
            }

            String speechText = transcript == null || transcript.isBlank()
                    ? ""
                    : "Speech transcript:\n" + transcript.strip() + "\n";
            String speechHeading = transcript == null || transcript.isBlank()
                    ? ""
                    : "Speech:\n";
            String transcriptStatus = transcript == null || transcript.isBlank() ? "no" : "yes";

            String prompt = """
                    You are summarizing a personal video named "%s" using sampled frame observations from across the video.

                    Start directly with the Summary heading. Do not write an introduction.
                    Write a practical description for organizing/searching videos. Mention only things that are in the
                    observations or transcript. Omit empty sections entirely. Do not mention absent categories, negative
                    facts, or phrases like "not visible", "not explicitly visible", "no speech", "none", "unknown",
                    "not available", or "transcript is not available". If no Speech transcript block is present below,
                    do not mention transcript, speech, audio, transcript availability, or what anyone says.
                    Ignore any mentions of comparison sheets, worksheets, labels, reference panels, reference pictures,
                    or video frame panels; those are analysis artifacts and are not video content.
                    Do not add "talking" or "speaking" as a tag unless a frame observation explicitly says a visible
                    person is talking/speaking or a Speech transcript block is present.

                    Use these headings when they have supported content:
                    Summary:
                    People:
                    Activities:
                    Places/scenes:
                    Notable objects:
                    %s
                    Tags:
                    Confidence:

                    Tags must contain only observed or transcribed content, for example people, kids, beach, swimming,
                    talking, indoor, outdoor, family, nature, pets, sports, party, vehicles, food, or travel when supported.
                    The Confidence section may say this is based on sampled frames. Mention transcript only when a
                    Speech transcript block is present below.

                    Duration: %s
                    Speech transcript block present: %s
                    Frame observations:
                    %s
                    %s
                    """.formatted(videoName, speechHeading, formatTimestamp(durationSeconds), transcriptStatus, frameText, speechText);
            return generate(prompt, List.of()).strip();
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
        var pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        var matcher = pattern.matcher(json);
        return matcher.find() ? unescapeJsonString(matcher.group(1)) : "";
    }

    static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append("\\u%04x".formatted((int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    static String unescapeJsonString(String value) {
        StringBuilder unescaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i == value.length() - 1) {
                unescaped.append(c);
                continue;
            }

            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"' -> unescaped.append('"');
                case '\\' -> unescaped.append('\\');
                case '/' -> unescaped.append('/');
                case 'b' -> unescaped.append('\b');
                case 'f' -> unescaped.append('\f');
                case 'n' -> unescaped.append('\n');
                case 'r' -> unescaped.append('\r');
                case 't' -> unescaped.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        unescaped.append("\\u");
                        continue;
                    }
                    String hex = value.substring(i + 1, i + 5);
                    try {
                        unescaped.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    } catch (NumberFormatException e) {
                        unescaped.append("\\u").append(hex);
                        i += 4;
                    }
                }
                default -> unescaped.append(escaped);
            }
        }
        return unescaped.toString();
    }
}
