package net.lckx;

/**
 * Reads and filters photo files from a Samsung phone connected to Mac.
 * Detects the phone via USB connection with comprehensive diagnostics.
 * Allows filtering photos by date range and retrieving file metadata.
 * User: louckxb, Date: 03/04/2026.
 */

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class Phone {
    private static final String[] PHOTO_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic", ".raw"};
    private static final String[] ANDROID_DIRS = {"DCIM", "Pictures", "Cameras", "Download", "MediaStore"};

    private Path phoneMountPath;
    private List<PhotoFile> photoFiles;

    /**
     * Attempts to auto-detect Samsung phone connection.
     */
    public Phone() throws IOException {
        this.photoFiles = new ArrayList<>();
        detectPhoneConnection();
    }

    /**
     * Initializes with a specific phone mount path or export directory.
     * Works with /Volumes mounts OR exported directories from OpenMTP.
     */
    public Phone(String mountPath) throws IOException {
        this.phoneMountPath = Paths.get(mountPath);
        this.photoFiles = new ArrayList<>();
        if (!Files.exists(this.phoneMountPath)) {
            throw new IOException("Path not found: " + mountPath);
        }
        if (!Files.isDirectory(this.phoneMountPath)) {
            throw new IOException("Path is not a directory: " + mountPath);
        }
    }

    /**
     * Detects phone by scanning /Volumes and checking for Android photo directories.
     */
    private void detectPhoneConnection() throws IOException {
        // Scan /Volumes for any connected device
        Path volumesPath = Paths.get("/Volumes");
        if (Files.exists(volumesPath)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(volumesPath)) {
                for (Path volume : stream) {
                    if (Files.isDirectory(volume)) {
                        String volName = volume.getFileName().toString();
                        if (!volName.equals("Macintosh HD")) {
                            // Check for Android photo directories
                            for (String androidDir : ANDROID_DIRS) {
                                Path photoDir = volume.resolve(androidDir);
                                if (Files.exists(photoDir) && Files.isDirectory(photoDir)) {
                                    this.phoneMountPath = volume;
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // Continue
            }
        }

        throw new IOException("Samsung phone not detected. Ensure phone is connected and unlocked.");
    }

    /**
     * Loads all photo files from the phone's gallery.
     */
    public void loadPhotos() throws IOException {
        photoFiles.clear();
        if (phoneMountPath == null) {
            throw new IOException("Phone not connected or detected.");
        }
        loadPhotosRecursive(phoneMountPath);
        photoFiles.sort(Comparator.comparing(PhotoFile::getModifiedDateTime));
        System.out.println("✓ Loaded " + photoFiles.size() + " photos from phone");
    }

    /**
     * Recursively loads photo files from a directory.
     */
    private void loadPhotosRecursive(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    loadPhotosRecursive(entry);
                } else if (isPhotoFile(entry)) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                        LocalDateTime modified = LocalDateTime.ofInstant(
                                attrs.lastModifiedTime().toInstant(),
                                ZoneId.systemDefault()
                        );
                        photoFiles.add(new PhotoFile(entry, modified, attrs.size()));
                    } catch (IOException e) {
                        // Skip files we can't read
                    }
                }
            }
        } catch (IOException e) {
            // Skip inaccessible directories
        }
    }

    /**
     * Checks if a file is a photo format.
     */
    private boolean isPhotoFile(Path file) {
        String filename = file.getFileName().toString().toLowerCase();
        for (String ext : PHOTO_EXTENSIONS) {
            if (filename.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    public List<PhotoFile> getPhotosByDate(LocalDate date) {
        return photoFiles.stream()
                .filter(p -> p.getModifiedDateTime().toLocalDate().equals(date))
                .collect(Collectors.toList());
    }

    public List<PhotoFile> getPhotosByDateRange(LocalDate startDate, LocalDate endDate) {
        return photoFiles.stream()
                .filter(p -> {
                    LocalDate photoDate = p.getModifiedDateTime().toLocalDate();
                    return !photoDate.isBefore(startDate) && !photoDate.isAfter(endDate);
                })
                .collect(Collectors.toList());
    }

    public List<PhotoFile> getPhotosByYearMonth(int year, int month) {
        return photoFiles.stream()
                .filter(p -> {
                    LocalDateTime dt = p.getModifiedDateTime();
                    return dt.getYear() == year && dt.getMonthValue() == month;
                })
                .collect(Collectors.toList());
    }

    public List<PhotoFile> getPhotosByYear(int year) {
        return photoFiles.stream()
                .filter(p -> p.getModifiedDateTime().getYear() == year)
                .collect(Collectors.toList());
    }

    public List<PhotoFile> getAllPhotos() {
        return new ArrayList<>(photoFiles);
    }

    public Path getPhoneMountPath() {
        return phoneMountPath;
    }

    public int getPhotoCount() {
        return photoFiles.size();
    }

    public void printPhotoSummary() {
        if (photoFiles.isEmpty()) {
            System.out.println("No photos loaded.");
            return;
        }

        System.out.println("\n=== Photo Summary ===");
        System.out.println("Total photos: " + photoFiles.size());
        System.out.println("Date range: " + photoFiles.get(0).getModifiedDateTime().toLocalDate() +
                " to " + photoFiles.get(photoFiles.size() - 1).getModifiedDateTime().toLocalDate());

        photoFiles.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getModifiedDateTime().toLocalDate(),
                        Collectors.counting()
                ))
                .forEach((date, count) -> System.out.println(date + ": " + count + " photos"));
    }

    /**
     * Represents a photo file with metadata.
     */
    public static class PhotoFile {
        private final Path path;
        private final LocalDateTime modifiedDateTime;
        private final long size;

        public PhotoFile(Path path, LocalDateTime modifiedDateTime, long size) {
            this.path = path;
            this.modifiedDateTime = modifiedDateTime;
            this.size = size;
        }

        public Path getPath() {
            return path;
        }

        public LocalDateTime getModifiedDateTime() {
            return modifiedDateTime;
        }

        public long getSize() {
            return size;
        }

        public String getFilename() {
            return path.getFileName().toString();
        }

        @Override
        public String toString() {
            return String.format("%s | %s | %d KB", getFilename(), modifiedDateTime, size / 1024);
        }
    }
}
