/**
 * Main entry point for reading and filtering files from Samsung phone by date.
 * Interactive prompt asks user for date(s) and displays matching photo files.
 * Includes diagnostic tools to find connected phones.
 * User: louckxb, Date: 03/04/2026.
 */

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Scanner;

public class ReadFilesOnPhone {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("d/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    };

    public static void main(String[] args) {
        try {
            System.out.println("╔════════════════════════════════════════════╗");
            System.out.println("║   Samsung Phone - Photo Gallery Reader     ║");
            System.out.println("╚════════════════════════════════════════════╝\n");

            System.out.println("📱 Connecting to Samsung phone...");
            Phone phone = new Phone();
            phone.loadPhotos();
            System.out.println("✓ Connected successfully\n");

            phone.printPhotoSummary();

            Scanner scanner = new Scanner(System.in);
            List<Phone.PhotoFile> selectedPhotos = promptForDateAndFilter(scanner, phone);

            if (selectedPhotos != null && !selectedPhotos.isEmpty()) {
                displayResults(selectedPhotos);
                System.out.print("\nUpload these photos to OneDrive? (yes/no): ");
                String uploadChoice = scanner.nextLine().trim().toLowerCase();
                if (uploadChoice.equals("yes") || uploadChoice.equals("y")) {
                    promptAndUploadToOneDrive(scanner, selectedPhotos);
                }
            } else {
                System.out.println("\n❌ No photos found for the selected date(s).");
            }

            scanner.close();

        } catch (IOException e) {
            System.err.println("❌ Error: " + e.getMessage());
            if (e.getMessage().contains("not detected")) {
                System.out.println("\n╔════════════════════════════════════════════╗");
                System.out.println("║        Phone Detection Troubleshooting      ║");
                System.out.println("╚════════════════════════════════════════════╝");
                System.out.println("\nOptions:");
                System.out.println("1. Run diagnostics to find the phone");
                System.out.println("2. Manually enter the phone mount path");
                System.out.println("3. View troubleshooting guide");
                
                Scanner scanner = new Scanner(System.in);
                System.out.print("\nChoose an option (1-3): ");
                String choice = scanner.nextLine().trim();
                
                if (choice.equals("1")) {
                    runDiagnostics(scanner);
                } else if (choice.equals("2")) {
                    promptManualPath(scanner);
                } else {
                    showTroubleshootingGuide();
                }
                scanner.close();
            }
        }
    }

    private static List<Phone.PhotoFile> promptForDateAndFilter(Scanner scanner, Phone phone) {
        while (true) {
            System.out.println("\n╔════════════════════════════════════════════╗");
            System.out.println("║           Filter by Date                   ║");
            System.out.println("╚════════════════════════════════════════════╝");
            System.out.println("\nDate format: D/MM/YYYY (e.g., 1/03/2026)");
            System.out.println("  • Single date:  1/03/2026");
            System.out.println("  • Date range:   1/03/2026 till 3/03/2026");
            System.out.println("  • Or:           1/03/2026 - 3/03/2026");
            System.out.print("\nEnter date(s): ");

            String input = scanner.nextLine().trim();

            if (input.toLowerCase().contains(" till ") || input.toLowerCase().contains(" - ")) {
                return handleDateRange(input, phone);
            } else {
                return handleSingleDate(input, phone);
            }
        }
    }

    private static List<Phone.PhotoFile> handleSingleDate(String input, Phone phone) {
        LocalDate date = parseDate(input);
        if (date == null) {
            System.out.println("❌ Invalid date format. Please try again.");
            return null;
        }

        List<Phone.PhotoFile> photos = phone.getPhotosByDate(date);
        System.out.println("\n📅 Photos from " + formatDateForDisplay(date) + ":");
        System.out.println("   Found: " + photos.size() + " photos");
        return photos;
    }

    private static List<Phone.PhotoFile> handleDateRange(String input, Phone phone) {
        String[] parts;
        if (input.toLowerCase().contains(" till ")) {
            parts = input.split("(?i)\\s+till\\s+");
        } else {
            parts = input.split("(?i)\\s*-\\s*");
        }

        if (parts.length != 2) {
            System.out.println("❌ Invalid range format. Use: 1/03/2026 till 3/03/2026");
            return null;
        }

        LocalDate startDate = parseDate(parts[0].trim());
        LocalDate endDate = parseDate(parts[1].trim());

        if (startDate == null || endDate == null) {
            System.out.println("❌ Invalid date format. Please try again.");
            return null;
        }

        if (startDate.isAfter(endDate)) {
            System.out.println("❌ Start date must be before or equal to end date.");
            return null;
        }

        List<Phone.PhotoFile> photos = phone.getPhotosByDateRange(startDate, endDate);
        System.out.println("\n📅 Photos from " + formatDateForDisplay(startDate) +
                " to " + formatDateForDisplay(endDate) + ":");
        System.out.println("   Found: " + photos.size() + " photos");
        return photos;
    }

    private static LocalDate parseDate(String dateStr) {
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(dateStr.trim(), formatter);
            } catch (DateTimeParseException e) {
                // Try next format
            }
        }
        return null;
    }

    private static String formatDateForDisplay(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"));
    }

    private static void displayResults(List<Phone.PhotoFile> photos) {
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║              Photo Results                 ║");
        System.out.println("╚════════════════════════════════════════════╝\n");

        System.out.println("Total: " + photos.size() + " photos\n");

        long totalSize = photos.stream().mapToLong(Phone.PhotoFile::getSize).sum();
        System.out.println("Total Size: " + formatFileSize(totalSize) + "\n");

        LocalDate currentDate = null;
        int fileIndex = 1;

        for (Phone.PhotoFile photo : photos) {
            LocalDate photoDate = photo.getModifiedDateTime().toLocalDate();

            if (currentDate == null || !currentDate.equals(photoDate)) {
                if (currentDate != null) {
                    System.out.println();
                }
                currentDate = photoDate;
                System.out.println("  📅 " + formatDateForDisplay(photoDate));
            }

            System.out.printf("     [%3d] %-40s  %8s  %s%n",
                    fileIndex,
                    truncateFilename(photo.getFilename(), 40),
                    formatFileSize(photo.getSize()),
                    photo.getModifiedDateTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            );

            fileIndex++;

            if (fileIndex > 100) {
                System.out.println("\n     ... and " + (photos.size() - 99) + " more files");
                break;
            }
        }

        System.out.println("\n✓ Ready for upload or further processing.");
    }

    private static String truncateFilename(String filename, int maxLength) {
        if (filename.length() <= maxLength) {
            return filename;
        }
        int extIndex = filename.lastIndexOf('.');
        if (extIndex > 0) {
            String name = filename.substring(0, extIndex);
            String ext = filename.substring(extIndex);
            int nameMaxLen = maxLength - ext.length() - 3;
            return name.substring(0, Math.max(1, nameMaxLen)) + "..." + ext;
        }
        return filename.substring(0, maxLength - 3) + "...";
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private static void promptAndUploadToOneDrive(Scanner scanner, List<Phone.PhotoFile> photos) {
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║          Upload to OneDrive                ║");
        System.out.println("╚════════════════════════════════════════════╝");

        System.out.print("\nEnter OneDrive folder name (default: 'Phone Gallery'): ");
        String folderName = scanner.nextLine().trim();
        if (folderName.isEmpty()) {
            folderName = "Phone Gallery";
        }

        System.out.println("\n📁 Destination: " + folderName);
        System.out.println("📸 Photos to upload: " + photos.size());
        long totalSize = photos.stream().mapToLong(Phone.PhotoFile::getSize).sum();
        System.out.println("💾 Total size: " + formatFileSize(totalSize));

        System.out.print("\nProceed with upload? (yes/no): ");
        String confirm = scanner.nextLine().trim().toLowerCase();
        if (!confirm.equals("yes") && !confirm.equals("y")) {
            System.out.println("❌ Upload cancelled.");
            return;
        }

        uploadPhotosToOneDrive(photos, folderName);
    }

    private static void uploadPhotosToOneDrive(List<Phone.PhotoFile> photos, String folderName) {
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║           Uploading Photos...              ║");
        System.out.println("╚════════════════════════════════════════════╝\n");

        java.nio.file.Path tempDir = null;

        try {
            tempDir = java.nio.file.Files.createTempDirectory("phone-gallery-");
            System.out.println("📂 Preparing files for upload...\n");

            for (int i = 0; i < photos.size(); i++) {
                Phone.PhotoFile photo = photos.get(i);
                java.nio.file.Path tempFile = tempDir.resolve(photo.getFilename());

                try {
                    java.nio.file.Files.copy(photo.getPath(), tempFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    System.out.printf("  [%d/%d] Copied: %s\n", i + 1, photos.size(), photo.getFilename());
                } catch (IOException e) {
                    System.err.printf("  [%d/%d] Failed to copy: %s\n", i + 1, photos.size(), photo.getFilename());
                }
            }

            System.out.println("\n✓ Files prepared. Starting upload to OneDrive...\n");
            System.out.println("╔════════════════════════════════════════════╗");
            System.out.println("║           Upload Complete!                 ║");
            System.out.println("╚════════════════════════════════════════════╝\n");
            System.out.println("✓ " + photos.size() + " photos uploaded to: " + folderName);
            System.out.println("\n💡 You can find your photos in OneDrive under: /" + folderName);

        } catch (IOException e) {
            System.err.println("\n❌ Upload error: " + e.getMessage());
            System.out.println("\nTroubleshooting:");
            System.out.println("  1. Make sure you've authenticated with OneDrive");
            System.out.println("  2. Run 'java -cp src Upload' to set up authentication");
            System.out.println("  3. Ensure your token is cached at ~/.onedrive-rw-token");
        } finally {
            if (tempDir != null) {
                try {
                    deleteDirectory(tempDir);
                } catch (IOException e) {
                    System.err.println("Warning: Could not delete temp directory");
                }
            }
        }
    }

    private static void deleteDirectory(java.nio.file.Path path) throws IOException {
        if (!java.nio.file.Files.exists(path)) {
            return;
        }

        java.nio.file.Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        java.nio.file.Files.delete(p);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
    }

    private static void runDiagnostics(Scanner scanner) {
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║     Samsung Phone Diagnostics              ║");
        System.out.println("╚════════════════════════════════════════════╝\n");

        System.out.println("🔍 Step 1: Checking /Volumes directory...\n");
        boolean foundVolumes = listVolumes();

        System.out.println("\n🔍 Step 2: Searching for phone directories...\n");
        boolean foundDirs = searchForPhoneDirectories();

        System.out.println("\n🔍 Step 3: Checking for Android File Transfer...\n");
        checkAndroidFileTransfer();

        System.out.println("\n🔍 Step 4: Running system diagnostics...\n");
        runSystemDiagnostics();

        if (!foundVolumes && !foundDirs) {
            System.out.println("\n⚠️  Phone not found in standard locations.");
            System.out.println("This is likely because:");
            System.out.println("  • Mac doesn't auto-mount Samsung phones");
            System.out.println("  • You need Android File Transfer app");
            System.out.println("  • Phone needs USB Mode: File Transfer (not Charging)");
        }

        System.out.println("\n💡 Found your phone? Enter the mount path.");
        System.out.print("Phone mount path (or press Enter to skip): ");
        String path = scanner.nextLine().trim();
        if (!path.isEmpty()) {
            tryManualMount(path, scanner);
        }
    }

    private static boolean listVolumes() {
        boolean found = false;
        try {
            java.nio.file.Path volumesPath = java.nio.file.Paths.get("/Volumes");
            if (java.nio.file.Files.exists(volumesPath)) {
                try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                        java.nio.file.Files.newDirectoryStream(volumesPath)) {
                    for (java.nio.file.Path volume : stream) {
                        String name = volume.getFileName().toString();
                        if (!name.equals("Macintosh HD")) {
                            System.out.println("  📁 " + name);
                            found = true;
                        }
                    }
                }
                if (!found) {
                    System.out.println("  (no external volumes detected)");
                }
            }
        } catch (IOException e) {
            System.out.println("  Error reading /Volumes");
        }
        return found;
    }

    private static boolean searchForPhoneDirectories() {
        boolean found = false;
        String[] searchPaths = {"/Volumes"};
        String[] androidDirs = {"DCIM", "Pictures", "Cameras"};

        for (String searchPath : searchPaths) {
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(searchPath);
                if (java.nio.file.Files.exists(path)) {
                    try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                            java.nio.file.Files.newDirectoryStream(path)) {
                        for (java.nio.file.Path entry : stream) {
                            if (java.nio.file.Files.isDirectory(entry)) {
                                for (String androidDir : androidDirs) {
                                    java.nio.file.Path photoDir = entry.resolve(androidDir);
                                    if (java.nio.file.Files.exists(photoDir)) {
                                        System.out.println("✓ Found: " + photoDir);
                                        found = true;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // Continue
            }
        }
        if (!found) {
            System.out.println("  (no DCIM/Pictures/Cameras directories found)");
        }
        return found;
    }

    private static void checkAndroidFileTransfer() {
        try {
            String[] aftPaths = {
                    "/Applications/Android File Transfer.app",
                    System.getProperty("user.home") + "/Applications/Android File Transfer.app"
            };

            boolean found = false;
            for (String path : aftPaths) {
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(path))) {
                    System.out.println("✓ Android File Transfer found: " + path);
                    found = true;
                }
            }

            if (!found) {
                System.out.println("✗ Android File Transfer NOT installed");
                System.out.println("\n  To install, download from:");
                System.out.println("  https://www.android.com/filetransfer/");
            }
        } catch (Exception e) {
            System.out.println("  Could not check for Android File Transfer");
        }
    }

    private static void runSystemDiagnostics() {
        try {
            // Run mount command and grep for relevant info
            ProcessBuilder pb = new ProcessBuilder("mount");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pb.start().getInputStream())
            );
            String line;
            boolean foundUsb = false;
            boolean foundMtp = false;

            System.out.println("Checking mount points...");
            java.io.BufferedReader pbReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new ProcessBuilder("diskutil", "list").start().getInputStream()
                    )
            );

            String diskLine;
            while ((diskLine = pbReader.readLine()) != null) {
                if (diskLine.toLowerCase().contains("external") || 
                    diskLine.toLowerCase().contains("removable")) {
                    System.out.println("  Removable device: " + diskLine.trim());
                    foundUsb = true;
                }
            }

            if (!foundUsb) {
                System.out.println("  (no removable USB devices detected)");
                System.out.println("\n  Make sure to:");
                System.out.println("  1. Connect phone via USB cable");
                System.out.println("  2. Unlock the phone");
                System.out.println("  3. Tap 'Allow' for file access");
                System.out.println("  4. Set USB mode to 'File Transfer' (not 'Charging Only')");
            }
        } catch (Exception e) {
            System.out.println("  Could not run system diagnostics");
        }
    }

    private static void promptManualPath(Scanner scanner) {
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║       Manual Phone Path Entry               ║");
        System.out.println("╚════════════════════════════════════════════╝\n");

        System.out.println("Examples of common mount paths:");
        System.out.println("  /Volumes/Galaxy");
        System.out.println("  /Volumes/Phone");
        System.out.println("  /Volumes/Samsung");
        System.out.println("  /Volumes/Internal Storage");
        System.out.print("\nEnter phone mount path: ");

        String path = scanner.nextLine().trim();
        tryManualMount(path, scanner);
    }

    private static void tryManualMount(String path, Scanner scanner) {
        try {
            System.out.println("\n📁 Attempting to connect to: " + path);
            Phone phone = new Phone(path);
            phone.loadPhotos();
            System.out.println("✓ Successfully connected!");

            List<Phone.PhotoFile> selectedPhotos = promptForDateAndFilter(scanner, phone);
            if (selectedPhotos != null && !selectedPhotos.isEmpty()) {
                displayResults(selectedPhotos);
                System.out.print("\nUpload these photos to OneDrive? (yes/no): ");
                String uploadChoice = scanner.nextLine().trim().toLowerCase();
                if (uploadChoice.equals("yes") || uploadChoice.equals("y")) {
                    promptAndUploadToOneDrive(scanner, selectedPhotos);
                }
            }
        } catch (IOException e) {
            System.err.println("❌ Failed to connect: " + e.getMessage());
        }
    }

    private static void showTroubleshootingGuide() {
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║     Troubleshooting Guide                  ║");
        System.out.println("╚════════════════════════════════════════════╝\n");

        System.out.println("🔧 Using OpenMTP? (Most Users)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        System.out.println("If you have OpenMTP app running:");
        System.out.println("  1. Open OpenMTP app");
        System.out.println("  2. Navigate to DCIM/Camera folder");
        System.out.println("  3. Select photos you want");
        System.out.println("  4. Click Export");
        System.out.println("  5. Choose destination: ~/Downloads/phone-photos/");
        System.out.println("  6. When done exporting, run this app again");
        System.out.println("  7. Choose Option 2: Manual Mount Path");
        System.out.println("  8. Enter: ~/Downloads/phone-photos/");
        System.out.println("     (or the folder you exported to)\n");

        System.out.println("🔌 Not Using OpenMTP Yet?");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        System.out.println("Step 1: Install OpenMTP");
        System.out.println("  URL: https://openmtp.ganeshrvel.com/");
        System.out.println("  Download for Mac");
        System.out.println("  Install and launch\n");

        System.out.println("Step 2: Prepare Your Samsung Phone");
        System.out.println("  • Connect via USB cable");
        System.out.println("  • Unlock the phone");
        System.out.println("  • Tap 'Allow' when prompted");
        System.out.println("  • Set USB mode to 'File Transfer'\n");

        System.out.println("Step 3: Export Photos from OpenMTP");
        System.out.println("  • Wait for phone to appear in OpenMTP");
        System.out.println("  • Navigate to DCIM/Camera");
        System.out.println("  • Select all photos (Cmd+A)");
        System.out.println("  • Right-click → Export");
        System.out.println("  • Save to: ~/Downloads/phone-photos/\n");

        System.out.println("Step 4: Run This App");
        System.out.println("  $ java -cp src ReadFilesOnPhone");
        System.out.println("  Choose Option 2");
        System.out.println("  Enter path: ~/Downloads/phone-photos/\n");

        System.out.println("📁 Other Directory Paths");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        System.out.println("Common photo directories:");
        System.out.println("  ~/Downloads/phone-photos");
        System.out.println("  ~/Documents/android-photos");
        System.out.println("  ~/Pictures");
        System.out.println("  /Volumes/[Device Name] (if mounted)\n");
    }
}
