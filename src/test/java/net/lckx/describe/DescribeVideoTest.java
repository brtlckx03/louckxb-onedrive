package net.lckx.describe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DescribeVideoTest {
    @TempDir
    Path tempDir;

    @Test
    void parseOptions_usesDefaults() {
        DescribeVideo.Options options = DescribeVideo.parseOptions(new String[]{"holiday.mp4"}, null, null);

        assertEquals(Path.of("holiday.mp4"), options.videoPath());
        assertEquals("qwen2.5vl:7b", options.model());
        assertEquals(URI.create("http://localhost:11434"), options.ollamaHost());
        assertEquals(8, options.frameCount());
        assertNull(options.sampleEverySeconds());
        assertEquals(512, options.imageWidth());
        assertEquals(Duration.ofMinutes(15), options.requestTimeout());
        assertFalse(options.frameCountExplicit());
        assertFalse(options.imageWidthExplicit());
        assertTrue(options.autoTune());
        assertFalse(options.randomSamples());
        assertNull(options.randomSeed());
        assertTrue(options.transcribeSpeech());
        assertFalse(options.transcribeSpeechExplicit());
        assertEquals("auto", options.transcriber());
        assertEquals("small", options.speechModel());
        assertEquals("auto", options.speechLanguage());
        assertEquals(Duration.ofMinutes(30), options.speechTimeout());
        assertEquals(Path.of(System.getProperty("user.dir"), "video-people").toAbsolutePath().normalize(), options.peopleDir());
        assertTrue(options.savePersonCandidates());
        assertEquals(8, options.maxPersonReferences());
        assertEquals("auto", options.personRecognition());
        assertEquals("python3", options.faceRecognitionPython());
        assertTrue(options.faceRecognitionScript().endsWith(Path.of("src/main/python/net/lckx/video/face_recognize.py")));
        assertEquals(0.6, options.faceRecognitionTolerance());
        assertNull(options.addPersonRequest());
        assertFalse(options.keepFrames());
        assertFalse(options.showFrameDetails());
    }

    @Test
    void parseOptions_acceptsOverrides() {
        DescribeVideo.Options options = DescribeVideo.parseOptions(new String[]{
                "--model", "llava:13b",
                "--host=127.0.0.1:11434",
                "--sample-every-seconds=5",
                "--random-seed=123",
                "--image-width", "384",
                "--timeout-minutes=30",
                "--transcribe",
                "--transcriber=whisper",
                "--speech-model", "base",
                "--speech-language", "nl",
                "--speech-timeout-minutes=45",
                "--people-dir", "~/video-person-library",
                "--no-save-person-candidates",
                "--max-person-refs=3",
                "--person-recognition=face",
                "--face-recognition-python", "~/face-venv/bin/python",
                "--face-recognition-tolerance", "0.5",
                "--keep-frames",
                "--details",
                "kids-at-beach.mp4"
        }, null, null);

        assertEquals(Path.of("kids-at-beach.mp4"), options.videoPath());
        assertEquals("llava:13b", options.model());
        assertEquals(URI.create("http://127.0.0.1:11434"), options.ollamaHost());
        assertEquals(8, options.frameCount());
        assertEquals(5, options.sampleEverySeconds());
        assertTrue(options.randomSamples());
        assertEquals(123L, options.randomSeed());
        assertEquals(384, options.imageWidth());
        assertEquals(Duration.ofMinutes(30), options.requestTimeout());
        assertFalse(options.frameCountExplicit());
        assertTrue(options.imageWidthExplicit());
        assertTrue(options.autoTune());
        assertTrue(options.transcribeSpeech());
        assertTrue(options.transcribeSpeechExplicit());
        assertEquals("whisper", options.transcriber());
        assertEquals("base", options.speechModel());
        assertEquals("nl", options.speechLanguage());
        assertEquals(Duration.ofMinutes(45), options.speechTimeout());
        assertEquals(Path.of(System.getProperty("user.home"), "video-person-library"), options.peopleDir());
        assertFalse(options.savePersonCandidates());
        assertEquals(3, options.maxPersonReferences());
        assertEquals("face", options.personRecognition());
        assertEquals(Path.of(System.getProperty("user.home"), "face-venv/bin/python").toString(), options.faceRecognitionPython());
        assertEquals(0.5, options.faceRecognitionTolerance());
        assertTrue(options.keepFrames());
        assertTrue(options.showFrameDetails());
    }

    @Test
    void parseOptions_usesEnvironmentOverrides() {
        DescribeVideo.Options options = DescribeVideo.parseOptions(
                new String[]{"clip.mov"},
                "qwen2.5vl:7b",
                "http://localhost:11435/"
        );

        assertEquals("qwen2.5vl:7b", options.model());
        assertEquals(URI.create("http://localhost:11435"), options.ollamaHost());
    }

    @Test
    void parseOptions_rejectsInvalidFrameCount() {
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--frames", "0", "clip.mp4"}, null, null));
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--frames", "51", "clip.mp4"}, null, null));
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--frames", "many", "clip.mp4"}, null, null));
    }

    @Test
    void parseOptions_rejectsFramesWithSampleInterval() {
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--frames", "12", "--sample-every-seconds", "5", "clip.mp4"}, null, null));
    }

    @Test
    void parseOptions_rejectsInvalidHost() {
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--host", "http://", "clip.mp4"}, null, null));
    }

    @Test
    void parseOptions_rejectsInvalidImageWidthAndTimeout() {
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--image-width", "64", "clip.mp4"}, null, null));
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--timeout-minutes", "0", "clip.mp4"}, null, null));
    }

    @Test
    void parseOptions_rejectsInvalidTranscriptionOptions() {
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--transcriber", "speech-to-text", "clip.mp4"}, null, null));
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--speech-timeout-minutes", "0", "clip.mp4"}, null, null));
    }

    @Test
    void parseOptions_canDisableAutoTune() {
        DescribeVideo.Options options = DescribeVideo.parseOptions(new String[]{"--no-auto-tune", "clip.mp4"}, null, null);

        assertFalse(options.autoTune());
    }

    @Test
    void parseOptions_canDisableDefaultTranscription() {
        DescribeVideo.Options options = DescribeVideo.parseOptions(new String[]{"--no-transcribe", "clip.mp4"}, null, null);

        assertFalse(options.transcribeSpeech());
        assertTrue(options.transcribeSpeechExplicit());
    }

    @Test
    void parseOptions_noKnownPeopleKeepsCandidateSavingEnabled() {
        DescribeVideo.Options options = DescribeVideo.parseOptions(new String[]{"--no-known-people", "clip.mp4"}, null, null);

        assertEquals(0, options.maxPersonReferences());
        assertEquals("off", options.personRecognition());
        assertTrue(options.savePersonCandidates());
    }

    @Test
    void parseOptions_rejectsInvalidPersonRecognitionOptions() {
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--person-recognition", "cloud", "clip.mp4"}, null, null));
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--face-recognition-tolerance", "2", "clip.mp4"}, null, null));
    }

    @Test
    void parseYesNoAnswer_defaultsToYesForBlankAnswer() {
        assertTrue(DescribeVideo.parseYesNoAnswer("", true));
        assertTrue(DescribeVideo.parseYesNoAnswer("   ", true));
        assertFalse(DescribeVideo.parseYesNoAnswer("", false));
    }

    @Test
    void parseYesNoAnswer_acceptsYesAndNoAnswers() {
        assertTrue(DescribeVideo.parseYesNoAnswer("y", true));
        assertTrue(DescribeVideo.parseYesNoAnswer("YES", false));
        assertFalse(DescribeVideo.parseYesNoAnswer("n", true));
        assertFalse(DescribeVideo.parseYesNoAnswer("No", true));
        assertNull(DescribeVideo.parseYesNoAnswer("maybe", true));
    }

    @Test
    void parseOptions_acceptsAddPersonModeWithoutVideo() {
        DescribeVideo.Options options = DescribeVideo.parseOptions(
                new String[]{"--people-dir", "/tmp/people", "--add-person", "Mila", "/tmp/mila.jpg"},
                null,
                null
        );

        assertNull(options.videoPath());
        assertEquals(Path.of("/tmp/people"), options.peopleDir());
        assertEquals("Mila", options.addPersonRequest().name());
        assertEquals(Path.of("/tmp/mila.jpg"), options.addPersonRequest().imagePath());
    }

    @Test
    void parseOptions_rejectsAddPersonCombinedWithVideo() {
        assertThrows(DescribeVideo.UsageException.class,
                () -> DescribeVideo.parseOptions(new String[]{"--add-person", "Mila", "/tmp/mila.jpg", "clip.mp4"}, null, null));
    }

    @Test
    void sampleTimes_spreadsFramesAcrossDuration() {
        List<Double> times = DescribeVideo.sampleTimes(100.0, 4);

        assertEquals(List.of(20.0, 40.0, 60.0, 80.0), times);
    }

    @Test
    void sampleTimes_singleFrameUsesMiddle() {
        assertEquals(List.of(15.0), DescribeVideo.sampleTimes(30.0, 1));
    }

    @Test
    void sampleTimesEvery_usesIntervalAcrossVideo() {
        assertEquals(List.of(5.0, 10.0), DescribeVideo.sampleTimesEvery(12.0, 5));
        assertEquals(List.of(3.0), DescribeVideo.sampleTimesEvery(6.0, 10));
    }

    @Test
    void sampleTimesRandom_usesOneRandomTimePerSegment() {
        List<Double> times = DescribeVideo.sampleTimesRandom(100.0, 4, new Random(1));

        assertEquals(4, times.size());
        assertTrue(times.get(0) >= 0.0 && times.get(0) < 25.0);
        assertTrue(times.get(1) >= 25.0 && times.get(1) < 50.0);
        assertTrue(times.get(2) >= 50.0 && times.get(2) < 75.0);
        assertTrue(times.get(3) >= 75.0 && times.get(3) <= 99.9);
        assertNotEquals(List.of(20.0, 40.0, 60.0, 80.0), times);
    }

    @Test
    void sampleTimesEveryRandom_usesOneRandomTimePerInterval() {
        List<Double> times = DescribeVideo.sampleTimesEveryRandom(12.0, 5, new Random(1));

        assertEquals(2, times.size());
        assertTrue(times.get(0) >= 0.0 && times.get(0) < 5.0);
        assertTrue(times.get(1) >= 5.0 && times.get(1) < 10.0);
        assertNotEquals(List.of(5.0, 10.0), times);
    }

    @Test
    void formatTimestamp_formatsMinutesAndHours() {
        assertEquals("01:01", DescribeVideo.formatTimestamp(61));
        assertEquals("01:01:01", DescribeVideo.formatTimestamp(3661));
    }

    @Test
    void formatDuration_includesShortDurations() {
        assertEquals("250ms", DescribeVideo.formatDuration(Duration.ofMillis(250)));
        assertEquals("1.5s", DescribeVideo.formatDuration(Duration.ofMillis(1_500)));
        assertEquals("2m 3s", DescribeVideo.formatDuration(Duration.ofSeconds(123)));
    }

    @Test
    void formatElapsedTimestamp_formatsStopwatchStyle() {
        assertEquals("+00:01.234", DescribeVideo.formatElapsedTimestamp(Duration.ofMillis(1_234)));
        assertEquals("+01:02:03.004", DescribeVideo.formatElapsedTimestamp(Duration.ofMillis(3_723_004)));
    }

    @Test
    void createAnalysisPlan_usesDurationAwareDefaults() {
        DescribeVideo.Options options = DescribeVideo.parseOptions(new String[]{"clip.mp4"}, null, null);

        DescribeVideo.AnalysisPlan plan = DescribeVideo.createAnalysisPlan(432.0, options);

        assertEquals(8, plan.frameCount());
        assertEquals(256, plan.imageWidth());
        assertTrue(plan.adjusted());
    }

    @Test
    void createAnalysisPlan_preservesExplicitValues() {
        DescribeVideo.Options options = DescribeVideo.parseOptions(
                new String[]{"--frames", "8", "--image-width", "512", "clip.mp4"},
                null,
                null
        );

        DescribeVideo.AnalysisPlan plan = DescribeVideo.createAnalysisPlan(432.0, options);

        assertEquals(8, plan.frameCount());
        assertEquals(512, plan.imageWidth());
        assertFalse(plan.adjusted());
    }

    @Test
    void createAnalysisPlan_usesSampleIntervalWhenRequested() {
        DescribeVideo.Options options = DescribeVideo.parseOptions(
                new String[]{"--sample-every-seconds", "5", "clip.mp4"},
                null,
                null
        );

        DescribeVideo.AnalysisPlan plan = DescribeVideo.createAnalysisPlan(432.0, options);

        assertEquals(86, plan.frameCount());
        assertEquals(256, plan.imageWidth());
        assertTrue(plan.adjusted());
        assertTrue(plan.reason().contains("every 5 seconds"));
    }

    @Test
    void nextAdaptiveImageWidth_stepsDownToMinimum() {
        assertEquals(256, DescribeVideo.nextAdaptiveImageWidth(512));
        assertEquals(128, DescribeVideo.nextAdaptiveImageWidth(256));
        assertEquals(128, DescribeVideo.nextAdaptiveImageWidth(128));
    }

    @Test
    void looksLikePersonObservation_detectsPeopleTerms() {
        assertTrue(DescribeVideo.looksLikePersonObservation("A young girl is smiling indoors."));
        assertTrue(DescribeVideo.looksLikePersonObservation("Portrait of an adult."));
        assertFalse(DescribeVideo.looksLikePersonObservation("A beach with water and sand."));
    }

    @Test
    void sanitizeFilename_replacesUnsafeCharacters() {
        assertEquals("Mila _ family", DescribeVideo.sanitizeFilename("Mila / family"));
        assertEquals("unnamed", DescribeVideo.sanitizeFilename("   "));
    }

    @Test
    void knownPeopleLoad_usesRenamedCandidateFilenameAsPersonName() throws Exception {
        Path videoDir = tempDir.resolve("verstoppertje_20240527_235745068");
        Files.createDirectories(videoDir);
        Files.writeString(videoDir.resolve("frame-01-01m26s.jpg"), "generated candidate");
        Path firstReference = videoDir.resolve("Miranda-01-01m26s.jpg");
        Path secondReference = videoDir.resolve("Miranda-02-02m53s.jpg");
        Path thirdReference = videoDir.resolve("Miranda_3.jpg");
        Files.writeString(firstReference, "renamed reference");
        Files.writeString(secondReference, "another renamed reference");
        Files.writeString(thirdReference, "legacy numbered reference");

        DescribeVideo.KnownPeople knownPeople = DescribeVideo.KnownPeople.load(tempDir, 10);

        assertEquals("Miranda", knownPeople.names());
        assertEquals(List.of(
                new DescribeVideo.KnownPersonReference("Miranda", firstReference),
                new DescribeVideo.KnownPersonReference("Miranda", secondReference),
                new DescribeVideo.KnownPersonReference("Miranda", thirdReference)
        ), knownPeople.references());
    }

    @Test
    void faceRecognitionParseMatchLine_readsNamesAndFaceCount() throws Exception {
        DescribeVideo.FaceRecognitionResult result = DescribeVideo.FaceRecognitionServer.parseMatchLine("MATCH\t2\tLotte\tMiranda\tLotte");

        assertEquals(2, result.faceCount());
        assertEquals(List.of("Lotte", "Miranda"), result.names());
        assertTrue(result.hasMatches());
        assertEquals("Lotte, Miranda", result.namesText());
    }

    @Test
    void faceRecognitionParseMatchLine_rejectsErrors() {
        assertThrows(IOException.class,
                () -> DescribeVideo.FaceRecognitionServer.parseMatchLine("ERROR\tpackage missing"));
    }

    @Test
    void jsonEscapeAndUnescape_roundTripsText() {
        String original = "talking \"near\" water\nkids and older adults";
        String escaped = DescribeVideo.jsonEscape(original);

        assertEquals(original, DescribeVideo.unescapeJsonString(escaped));
        assertEquals(original, DescribeVideo.jsonString("{\"response\":\"" + escaped + "\"}", "response"));
    }

    @Test
    void removeAbsentContent_removesNegativeSectionsAndLines() {
        String summary = """
                **Summary**
                A child is indoors.

                **People**
                - Child

                **Speech**
                - No speech observed in this frame

                **Notable objects**
                None explicitly visible

                **Tags**
                - child
                - indoors

                **Confidence**
                The transcript is not available, but this summary is based on sampled frames.

                **Activities**: None mentioned

                **Transcript**: The person says "Hello, I'm [name]."
                """;

        String cleaned = DescribeVideo.removeAbsentContent(summary);

        assertTrue(cleaned.contains("A child is indoors."));
        assertTrue(cleaned.contains("- Child"));
        assertTrue(cleaned.contains("- child"));
        assertFalse(cleaned.contains("No speech"));
        assertFalse(cleaned.contains("None explicitly"));
        assertFalse(cleaned.contains("transcript is not available"));
        assertFalse(cleaned.contains("None mentioned"));
        assertFalse(cleaned.contains("The person says"));
        assertFalse(cleaned.contains("[name]"));
        assertFalse(cleaned.contains("**Speech**"));
        assertFalse(cleaned.contains("**Notable objects**"));
        assertFalse(cleaned.contains("**Confidence**"));
    }
}
