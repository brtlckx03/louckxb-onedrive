package net.lckx.phone; /**
 * Reads and filters photo files directly from Android phone via ADB (Android Debug Bridge).
 * Connects to phone through USB without requiring manual export.
 * User: louckxb, Date: 03/04/2026.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class AndroidPhone {
    private static final String[] PHOTO_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic"};
    private boolean adbAvailable = false;
    private final List<PhotoFile> photoFiles;
    private long loadTimeMs = 0;
    private long filterTimeMs = 0;

    public AndroidPhone() throws IOException {
        this.photoFiles = new ArrayList<>();
        checkAdbAvailable();
        if (!adbAvailable) {
            throw new IOException("ADB not found. Please install Android SDK Platform Tools.");
        }
    }

    /**
     * Checks if ADB is available on the system.
     */
    private void checkAdbAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("adb", "version");
            Process p = pb.start();
            int exitCode = p.waitFor();
            adbAvailable = (exitCode == 0);
        } catch (Exception e) {
            adbAvailable = false;
        }
    }

    /**
     * Gets list of connected Android devices.
     */
    public List<String> getConnectedDevices() throws IOException {
        List<String> devices = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("adb", "devices");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(pb.start().getInputStream())
            );
            String line;
            boolean headerPassed = false;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("List of")) {
                    headerPassed = true;
                    continue;
                }
                if (headerPassed && !line.isEmpty() && line.contains("device")) {
                    String device = line.split("\\s+")[0];
                    if (!device.isEmpty() && !device.equals("emulator-5554")) {
                        devices.add(device);
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Error listing devices: " + e.getMessage());
        }
        return devices;
    }

    /**
     * Loads all photos from phone's DCIM/Camera directory via ADB.
     */
    public void loadPhotos() throws IOException {
        long startTime = System.currentTimeMillis();
        photoFiles.clear();

        List<String> devices = getConnectedDevices();
        if (devices.isEmpty()) {
            throw new IOException("No Android devices connected. Connect phone via USB and enable USB Debugging.");
        }

        String device = devices.getFirst();
        System.out.println("Connected to device: " + device);

        // Try common photo directories (in order of preference)
        String[] photoDirs = {
                "/sdcard/DCIM/Camera",
                "/sdcard/SdCardBackUp/DCIM/Camera",
                "/sdcard/WhatsApp/Media/WhatsApp Images",
                "/sdcard/DCIM",
                "/sdcard/Pictures",
                "/sdcard/WhatsApp/Media/WhatsApp Video",
                "/storage/emulated/0/DCIM/Camera",
                "/storage/emulated/0/Pictures"
        };

        //2013-07-16
        //  [99/1252] Failed: 2016-04-22
        //  [100/1252] Downloaded: IMG_20160826_095031.jpg
        for (String dir : photoDirs) {
            try {
                loadPhotosFromDirectory(device, dir);
                if (!photoFiles.isEmpty()) {
                    System.out.println("✓ Found " + photoFiles.size() + " photos in: " + dir);
                    break;
                }
            } catch (IOException e) {
                // Try next directory
            }
        }

        if (photoFiles.isEmpty()) {
            throw new IOException("No photos found in DCIM or Pictures directories");
        }

        photoFiles.sort(Comparator.comparing(PhotoFile::modifiedDateTime));

        loadTimeMs = System.currentTimeMillis() - startTime;
    }

    /**
     * Loads photos from a custom directory on the phone via ADB.
     * Useful for accessing backup folders or non-standard locations.
     *
     * @param customDir The full path to the directory on the phone (e.g., "/sdcard/SdCardBackUp/DCIM/Camera")
     * @throws IOException if directory doesn't exist or can't be read
     */
    public void loadPhotosFromCustomDirectory(String customDir) throws IOException {
        long startTime = System.currentTimeMillis();
        photoFiles.clear();

        List<String> devices = getConnectedDevices();
        if (devices.isEmpty()) {
            throw new IOException("No Android devices connected. Connect phone via USB and enable USB Debugging.");
        }

        String device = devices.getFirst();
        System.out.println("Connected to device: " + device);
        System.out.println("Loading photos from custom directory: " + customDir);

        try {
            loadPhotosFromDirectory(device, customDir);
            if (!photoFiles.isEmpty()) {
                System.out.println("✓ Found " + photoFiles.size() + " photos in: " + customDir);
            } else {
                throw new IOException("No photos found in: " + customDir);
            }
        } catch (IOException e) {
            throw new IOException("Failed to load from custom directory: " + e.getMessage());
        }

        photoFiles.sort(Comparator.comparing(PhotoFile::modifiedDateTime));

        loadTimeMs = System.currentTimeMillis() - startTime;
    }

    /**
     * Loads photos from a specific directory via ADB using ls -l for fast batch retrieval.
     * This is the primary method - works on all Android systems.
     * Optimized for large number of files.
     */
    private void loadPhotosFromDirectory(String device, String dir) throws IOException {
        try {
            // Use ls -l to get all files with metadata in one command (non-recursive)
            // Quote directory path to handle spaces in directory names
            ProcessBuilder pb = new ProcessBuilder("adb", "-s", device, "shell", "ls -l '" + dir + "'");
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                // Skip first line (total) and empty lines
                if (lineCount == 1 || line.trim().isEmpty()) {
                    continue;
                }

                // Fast path: only process if line likely contains photo extension
                String lowerLine = line.toLowerCase();
                if (!isPhotoFile(lowerLine)) {
                    continue;
                }

                try {
                    // Parse ls -l output: -rw-rw---- 1 u0_a293 media_rw 4488849 2021-08-09 16:17 filename.jpg
                    // When split on whitespace:
                    // [0]=perms, [1]=links, [2]=user, [3]=group, [4]=size, [5]=date, [6]=time, [7+]=filename (may have spaces)
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 8) {
                        long size = Long.parseLong(parts[4]);
                        String dateStr = parts[5];  // YYYY-MM-DD
                        String timeStr = parts[6];  // HH:MM

                        // Filename may contain spaces, so join all parts from index 7 onwards
                        StringBuilder filenameBuilder = new StringBuilder();
                        for (int i = 7; i < parts.length; i++) {
                            if (i > 7) filenameBuilder.append(" ");
                            filenameBuilder.append(parts[i]);
                        }
                        String filename = filenameBuilder.toString();

                        try {
                            LocalDateTime modTime = LocalDateTime.parse(
                                    dateStr + "T" + timeStr + ":00",
                                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                            );

                            String fullPath = dir + "/" + filename;
                            photoFiles.add(new PhotoFile(filename, fullPath, modTime, size, device));
                        } catch (Exception e) {
                            // Skip this file, invalid datetime
                        }
                    }
                } catch (NumberFormatException e) {
                    // Skip files with invalid size - probably not a file entry
                }
            }

            reader.close();
            process.waitFor();
        } catch (Exception e) {
            throw new IOException("Could not read directory: " + dir);
        }
    }

    /**
     * Parses various datetime formats from ls/stat output.
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            // Try ISO format: YYYY-MM-DD|HH:MM:SS
            if (dateTimeStr.contains("|")) {
                String[] parts = dateTimeStr.split("\\|");
                if (parts.length == 2) {
                    return LocalDateTime.parse(parts[0] + "T" + parts[1],
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                }
            }
            // Try ls format: MMM DD HH:MM or MMM DD YYYY
            DateTimeFormatter[] formats = {
                    DateTimeFormatter.ofPattern("MMM dd HH:mm"),
                    DateTimeFormatter.ofPattern("MMM dd yyyy"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            };
            for (DateTimeFormatter fmt : formats) {
                try {
                    return LocalDateTime.parse(dateTimeStr + ":00", fmt.withZone(ZoneId.systemDefault()));
                } catch (Exception e) {
                    // Try next format
                }
            }
        } catch (Exception e) {
            // Fall through to default
        }
        return LocalDateTime.now();
    }

    /**
     * Checks if a line from ls output is a photo file.
     */
    private boolean isPhotoFile(String line) {
        String lower = line.toLowerCase();
        for (String ext : PHOTO_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    public List<PhotoFile> getPhotosByDate(LocalDate date) {
        long startTime = System.currentTimeMillis();
        List<PhotoFile> results = photoFiles.stream()
                .filter(p -> p.modifiedDateTime().toLocalDate().equals(date))
                .collect(Collectors.toList());
        filterTimeMs = System.currentTimeMillis() - startTime;
        return results;
    }

    public List<PhotoFile> getPhotosByDateRange(LocalDate startDate, LocalDate endDate) {
        long startTime = System.currentTimeMillis();
        List<PhotoFile> results = photoFiles.stream()
                .filter(p -> {
                    LocalDate photoDate = p.modifiedDateTime().toLocalDate();
                    return !photoDate.isBefore(startDate) && !photoDate.isAfter(endDate);
                })
                .collect(Collectors.toList());
        filterTimeMs = System.currentTimeMillis() - startTime;
        return results;
    }

    public List<PhotoFile> getPhotosByYearMonth(int year, int month) {
        return photoFiles.stream()
                .filter(p -> {
                    LocalDateTime dt = p.modifiedDateTime();
                    return dt.getYear() == year && dt.getMonthValue() == month;
                })
                .collect(Collectors.toList());
    }

    public List<PhotoFile> getAllPhotos() {
        return new ArrayList<>(photoFiles);
    }

    public int getPhotoCount() {
        return photoFiles.size();
    }

    public long getLoadTimeMs() {
        return loadTimeMs;
    }

    public long getFilterTimeMs() {
        return filterTimeMs;
    }

    public String formatTime(long milliseconds) {
        if (milliseconds < 1000) {
            return milliseconds + "ms";
        }
        return String.format("%.2f seconds", milliseconds / 1000.0);
    }

    public String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int units = 0;
        double size = bytes;
        while (size >= 1024 && units < 4) {
            size /= 1024.0;
            units++;
        }
        String[] unitNames = {"B", "KB", "MB", "GB", "TB"};
        return String.format("%.2f %s", size, unitNames[units]);
    }

    public void printPhotoSummary() {
        if (photoFiles.isEmpty()) {
            System.out.println("No photos loaded.");
            return;
        }

        System.out.println("\n=== Photo Summary ===");
        System.out.println("Total photos: " + photoFiles.size());
        System.out.println("Date range: " + photoFiles.getFirst().modifiedDateTime().toLocalDate() +
                " to " + photoFiles.getLast().modifiedDateTime().toLocalDate());

        photoFiles.stream()
                .collect(Collectors.groupingBy(
                        p -> p.modifiedDateTime().toLocalDate(),
                        Collectors.counting()
                ))
                .forEach((date, count) -> System.out.println(date + ": " + count + " photos"));
    }

    /**
         * Represents a photo file on Android device.
         */
        public record PhotoFile(String filename, String remotePath, LocalDateTime modifiedDateTime, long size, String device) {

        @Override
            public String toString() {
                return String.format("%s | %s | %d KB", filename, modifiedDateTime, size / 1024);
            }
        }
}
