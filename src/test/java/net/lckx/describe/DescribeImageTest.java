package net.lckx.describe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class DescribeImageTest {
    @TempDir
    Path tempDir;

    @Test
    void parseOptions_usesDefaults() {
        DescribeImage.Options options = DescribeImage.parseOptions(new String[]{"photo.jpg"}, null, null);

        assertEquals(Path.of("photo.jpg"), options.imagePath());
        assertEquals("qwen2.5vl:7b", options.model());
        assertEquals(URI.create("http://localhost:11434"), options.ollamaHost());
        assertEquals(Duration.ofMinutes(15), options.requestTimeout());
        assertEquals(Path.of(System.getProperty("user.dir"), "video-people").toAbsolutePath().normalize(), options.peopleDir());
        assertEquals(8, options.maxPersonReferences());
        assertEquals("auto", options.personRecognition());
        assertFalse(options.faceRecognitionPython().isBlank());
        assertTrue(options.faceRecognitionScript().endsWith(Path.of("src/main/python/net/lckx/video/face_recognize.py")));
        assertEquals(0.6, options.faceRecognitionTolerance());
        assertNull(options.addPersonRequest());
        assertFalse(options.showDetails());
    }

    @Test
    void parseOptions_acceptsOverrides() {
        DescribeImage.Options options = DescribeImage.parseOptions(new String[]{
                "--model", "llava:13b",
                "--host=127.0.0.1:11434",
                "--timeout-minutes=30",
                "--people-dir", "~/video-person-library",
                "--max-person-refs=3",
                "--person-recognition=face",
                "--face-recognition-python", "~/face-venv/bin/python",
                "--face-recognition-script", "~/helpers/face_recognize.py",
                "--face-recognition-tolerance", "0.5",
                "--details",
                "kids-at-beach.jpg"
        }, null, null);

        assertEquals(Path.of("kids-at-beach.jpg"), options.imagePath());
        assertEquals("llava:13b", options.model());
        assertEquals(URI.create("http://127.0.0.1:11434"), options.ollamaHost());
        assertEquals(Duration.ofMinutes(30), options.requestTimeout());
        assertEquals(Path.of(System.getProperty("user.home"), "video-person-library"), options.peopleDir());
        assertEquals(3, options.maxPersonReferences());
        assertEquals("face", options.personRecognition());
        assertEquals(Path.of(System.getProperty("user.home"), "face-venv/bin/python").toString(), options.faceRecognitionPython());
        assertEquals(Path.of(System.getProperty("user.home"), "helpers/face_recognize.py"), options.faceRecognitionScript());
        assertEquals(0.5, options.faceRecognitionTolerance());
        assertTrue(options.showDetails());
    }

    @Test
    void parseOptions_usesEnvironmentOverrides() {
        DescribeImage.Options options = DescribeImage.parseOptions(
                new String[]{"photo.png"},
                "qwen2.5vl:7b",
                "http://localhost:11435/"
        );

        assertEquals("qwen2.5vl:7b", options.model());
        assertEquals(URI.create("http://localhost:11435"), options.ollamaHost());
    }

    @Test
    void parseOptions_noKnownPeopleDisablesRecognitionOnly() {
        DescribeImage.Options options = DescribeImage.parseOptions(new String[]{"--no-known-people", "photo.jpg"}, null, null);

        assertEquals(0, options.maxPersonReferences());
        assertEquals("off", options.personRecognition());
    }

    @Test
    void parseOptions_acceptsAddPersonModeWithoutImageToDescribe() {
        DescribeImage.Options options = DescribeImage.parseOptions(
                new String[]{"--people-dir", "/tmp/people", "--add-person", "Mila", "/tmp/mila.jpg"},
                null,
                null
        );

        assertNull(options.imagePath());
        assertEquals(Path.of("/tmp/people"), options.peopleDir());
        assertEquals("Mila", options.addPersonRequest().name());
        assertEquals(Path.of("/tmp/mila.jpg"), options.addPersonRequest().imagePath());
    }

    @Test
    void parseOptions_allowsMissingImagePathForInteractivePrompt() {
        DescribeImage.Options options = DescribeImage.parseOptions(new String[]{}, null, null);

        assertNull(options.imagePath());
        assertNull(options.addPersonRequest());
    }

    @Test
    void parseOptions_rejectsInvalidOptions() {
        assertThrows(DescribeImage.UsageException.class,
                () -> DescribeImage.parseOptions(new String[]{"one.jpg", "two.jpg"}, null, null));
        assertThrows(DescribeImage.UsageException.class,
                () -> DescribeImage.parseOptions(new String[]{"--host", "http://", "photo.jpg"}, null, null));
        assertThrows(DescribeImage.UsageException.class,
                () -> DescribeImage.parseOptions(new String[]{"--timeout-minutes", "0", "photo.jpg"}, null, null));
        assertThrows(DescribeImage.UsageException.class,
                () -> DescribeImage.parseOptions(new String[]{"--person-recognition", "cloud", "photo.jpg"}, null, null));
        assertThrows(DescribeImage.UsageException.class,
                () -> DescribeImage.parseOptions(new String[]{"--face-recognition-tolerance", "2", "photo.jpg"}, null, null));
        assertThrows(DescribeImage.UsageException.class,
                () -> DescribeImage.parseOptions(new String[]{"--add-person", "Mila", "/tmp/mila.jpg", "photo.jpg"}, null, null));
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

        DescribeImage.KnownPeople knownPeople = DescribeImage.KnownPeople.load(tempDir, 10);

        assertEquals("Miranda", knownPeople.names());
        assertEquals(List.of(
                new DescribeImage.KnownPersonReference("Miranda", firstReference),
                new DescribeImage.KnownPersonReference("Miranda", secondReference),
                new DescribeImage.KnownPersonReference("Miranda", thirdReference)
        ), knownPeople.references());
    }

    @Test
    void faceRecognitionParseMatchLine_readsNamesAndFaceCount() throws Exception {
        DescribeImage.FaceRecognitionResult result = DescribeImage.FaceRecognitionServer.parseMatchLine("MATCH\t2\tLotte\tMiranda\tLotte");

        assertEquals(2, result.faceCount());
        assertEquals(List.of("Lotte", "Miranda"), result.names());
        assertTrue(result.hasMatches());
        assertEquals("Lotte, Miranda", result.namesText());
    }

    @Test
    void faceRecognitionParseMatchLine_rejectsErrors() {
        assertThrows(IOException.class,
                () -> DescribeImage.FaceRecognitionServer.parseMatchLine("ERROR\tpackage missing"));
    }

    @Test
    void addPersonMode_savesNormalizedReferenceImage() throws Exception {
        Path source = tempDir.resolve("mila.png");
        writeTestImage(source, 20, 10);
        Path peopleDir = tempDir.resolve("people");

        int exitCode = new DescribeImage().run(new String[]{
                "--people-dir", peopleDir.toString(),
                "--add-person", "Mila", source.toString()
        });

        assertEquals(0, exitCode);
        Path reference = peopleDir.resolve("Mila.jpg");
        assertTrue(Files.isRegularFile(reference));
        BufferedImage saved = ImageIO.read(reference.toFile());
        assertNotNull(saved);
        assertEquals(20, saved.getWidth());
        assertEquals(10, saved.getHeight());
    }

    @Test
    void jsonEscapeAndUnescape_roundTripsText() {
        String original = "photo \"near\" water\nkids and older adults";
        String escaped = DescribeImage.jsonEscape(original);

        assertEquals(original, DescribeImage.unescapeJsonString(escaped));
        assertEquals(original, DescribeImage.jsonString("{\"response\":\"" + escaped + "\"}", "response"));
    }

    @Test
    void removeAbsentContent_removesNegativeSectionsAndLines() {
        String summary = """
                **Summary**
                A child is indoors.

                **People**
                - Child

                **Text**
                - No text visible

                **Notable objects**
                None explicitly visible

                **Tags**
                - child
                - indoors

                **Confidence**
                This is based on one image.
                """;

        String cleaned = DescribeImage.removeAbsentContent(summary);

        assertTrue(cleaned.contains("A child is indoors."));
        assertTrue(cleaned.contains("- Child"));
        assertTrue(cleaned.contains("- child"));
        assertFalse(cleaned.contains("No text"));
        assertFalse(cleaned.contains("None explicitly"));
        assertFalse(cleaned.contains("**Text**"));
        assertFalse(cleaned.contains("**Notable objects**"));
        assertTrue(cleaned.contains("This is based on one image."));
    }

    private static void writeTestImage(Path path, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, Color.BLUE.getRGB());
            }
        }
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }
}
