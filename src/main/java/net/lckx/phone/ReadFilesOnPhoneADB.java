package net.lckx.phone; /**
 * Main entry point for reading and filtering files from Samsung phone via ADB.
 * Direct connection via Android Debug Bridge - no manual export needed.
 * User: louckxb, Date: 03/04/2026.
 */

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Scanner;

public class ReadFilesOnPhoneADB {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("d/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    };

    public static void main(String[] args) {
        try {
            System.out.println("╔════════════════════════════════════════════╗");
            System.out.println("║   Samsung Phone - Direct ADB Connection    ║");
            System.out.println("╚════════════════════════════════════════════╝\n");

            System.out.println("🔗 Connecting to Samsung phone via ADB...");
            AndroidPhone phone = new AndroidPhone();

            System.out.println("📱 Loading photos from phone...");
            phone.loadPhotos();
            System.out.println("✓ Connected successfully\n");
            System.out.println("⏱️  Load time: " + phone.formatTime(phone.getLoadTimeMs()) + "\n");

            phone.printPhotoSummary();

            Scanner scanner = new Scanner(System.in);
            boolean continueSearching = true;

            while (continueSearching) {
                List<AndroidPhone.PhotoFile> selectedPhotos = promptForDateAndFilter(scanner, phone);

                if (selectedPhotos != null && !selectedPhotos.isEmpty()) {
                    displayResults(selectedPhotos, phone);
                    System.out.print("\nDownload these photos from phone? (yes/no): ");
                    String downloadChoice = scanner.nextLine().trim().toLowerCase();
                    if (downloadChoice.equals("yes") || downloadChoice.equals("y")) {
                        promptAndDownloadPhotos(scanner, selectedPhotos);
                    }
                } else {
                    System.out.println("\n❌ No photos found for the selected date(s).");
                }

                System.out.println("\n╔════════════════════════════════════════════╗");
                System.out.print("Search more photos? (yes/no/exit): ");
                String continueChoice = scanner.nextLine().trim().toLowerCase();

                if (continueChoice.equals("exit") || continueChoice.equals("quit") || continueChoice.equals("q")) {
                    continueSearching = false;
                } else if (!continueChoice.equals("yes") && !continueChoice.equals("y")) {
                    continueSearching = false;
                }
            }

            System.out.println("\n✓ Thank you for using Samsung Photo Manager. Goodbye!");
            scanner.close();

        } catch (IOException e) {
            System.err.println("❌ Error: " + e.getMessage());
            showAdbSetupGuide();
        }
    }

    private static List<AndroidPhone.PhotoFile> promptForDateAndFilter(Scanner scanner, AndroidPhone phone) {
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

    private static List<AndroidPhone.PhotoFile> handleSingleDate(String input, AndroidPhone phone) {
        LocalDate date = parseDate(input);
        if (date == null) {
            System.out.println("❌ Invalid date format. Please try again.");
            return null;
        }

        List<AndroidPhone.PhotoFile> photos = phone.getPhotosByDate(date);
        System.out.println("\n📅 Photos from " + formatDateForDisplay(date) + ":");
        System.out.println("   Found: " + photos.size() + " photos");
        System.out.println("   ⏱️  Search time: " + phone.formatTime(phone.getFilterTimeMs()));
        return photos;
    }

    private static List<AndroidPhone.PhotoFile> handleDateRange(String input, AndroidPhone phone) {
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

        List<AndroidPhone.PhotoFile> photos = phone.getPhotosByDateRange(startDate, endDate);
        System.out.println("\n📅 Photos from " + formatDateForDisplay(startDate) +
                " to " + formatDateForDisplay(endDate) + ":");
        System.out.println("   Found: " + photos.size() + " photos");
        System.out.println("   ⏱️  Search time: " + phone.formatTime(phone.getFilterTimeMs()));
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

    private static void displayResults(List<AndroidPhone.PhotoFile> photos, AndroidPhone phone) {
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║              Photo Results                 ║");
        System.out.println("╚════════════════════════════════════════════╝\n");

        System.out.println("Total: " + photos.size() + " photos\n");

        long totalSize = photos.stream().mapToLong(AndroidPhone.PhotoFile::size).sum();
        System.out.println("Total Size: " + formatFileSize(totalSize) + "\n");

        LocalDate currentDate = null;
        int fileIndex = 1;

        for (AndroidPhone.PhotoFile photo : photos) {
            LocalDate photoDate = photo.modifiedDateTime().toLocalDate();

            if (currentDate == null || !currentDate.equals(photoDate)) {
                if (currentDate != null) {
                    System.out.println();
                }
                currentDate = photoDate;
                System.out.println("  📅 " + formatDateForDisplay(photoDate));
            }

            System.out.printf("     [%3d] %-40s  %8s  %s%n",
                    fileIndex,
                    truncateFilename(photo.filename(), 40),
                    formatFileSize(photo.size()),
                    photo.modifiedDateTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            );

            fileIndex++;

            if (fileIndex > 100) {
                System.out.println("\n     ... and " + (photos.size() - 99) + " more files");
                break;
            }
        }

        System.out.println("\n✓ Ready for download or further processing.");
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

    private static void promptAndDownloadPhotos(Scanner scanner, List<AndroidPhone.PhotoFile> photos) {
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║      Download to Mac                       ║");
        System.out.println("╚════════════════════════════════════════════╝");

        System.out.print("\nEnter local folder path (default: ~/Downloads/phone-photos/): ");
        String folderPath = scanner.nextLine().trim();
        if (folderPath.isEmpty()) {
            folderPath = System.getProperty("user.home") + "/Downloads/phone-photos/";
        } else if (folderPath.startsWith("~/")) {
            folderPath = System.getProperty("user.home") + folderPath.substring(1);
        }

        System.out.println("\n📁 Destination: " + folderPath);
        System.out.println("📸 Photos to download: " + photos.size());
        long totalSize = photos.stream().mapToLong(AndroidPhone.PhotoFile::size).sum();
        System.out.println("💾 Total size: " + formatFileSize(totalSize));

        System.out.print("\nProceed with download? (yes/no): ");
        String confirm = scanner.nextLine().trim().toLowerCase();
        if (!confirm.equals("yes") && !confirm.equals("y")) {
            System.out.println("❌ Download cancelled.");
            return;
        }

        downloadPhotos(photos, folderPath);
    }

    private static void downloadPhotos(List<AndroidPhone.PhotoFile> photos, String localFolder) {
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║        Downloading Photos...               ║");
        System.out.println("╚════════════════════════════════════════════╝\n");

        try {
            // Create local folder
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(localFolder));

            int successCount = 0;
            for (int i = 0; i < photos.size(); i++) {
                AndroidPhone.PhotoFile photo = photos.get(i);
                String localPath = localFolder + photo.filename();

                try {
                    ProcessBuilder pb = new ProcessBuilder("adb", "-s", photo.device(),
                            "pull", photo.remotePath(), localPath);
                    int exitCode = pb.start().waitFor();

                    if (exitCode == 0) {
                        System.out.printf("  [%d/%d] Downloaded: %s\n", i + 1, photos.size(), photo.filename());
                        successCount++;
                    } else {
                        System.err.printf("  [%d/%d] Failed: %s\n", i + 1, photos.size(), photo.filename());
                    }
                } catch (Exception e) {
                    System.err.printf("  [%d/%d] Error: %s\n", i + 1, photos.size(), photo.filename());
                }
            }

            System.out.println("\n╔════════════════════════════════════════════╗");
            System.out.println("║        Download Complete!                  ║");
            System.out.println("╚════════════════════════════════════════════╝\n");
            System.out.println("✓ " + successCount + " of " + photos.size() + " photos downloaded");
            System.out.println("📁 Location: " + localFolder);

        } catch (IOException e) {
            System.err.println("\n❌ Download error: " + e.getMessage());
        }
    }

    private static void showAdbSetupGuide() {
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║       ADB Setup Required                   ║");
        System.out.println("╚════════════════════════════════════════════╝\n");

        System.out.println("📋 ADB (Android Debug Bridge) not found\n");
        System.out.println("Setup Steps:\n");
        System.out.println("1. Install Android SDK Platform Tools:");
        System.out.println("   Option A: Using Homebrew (recommended)");
        System.out.println("     $ brew install android-platform-tools");
        System.out.println("   Option B: Download from Google");
        System.out.println("     https://developer.android.com/studio/releases/platform-tools\n");

        System.out.println("2. Enable USB Debugging on your phone:");
        System.out.println("   Settings → Developer Options → USB Debugging (ON)");
        System.out.println("   (If Developer Options not visible:");
        System.out.println("    Settings → About Phone → Build Number (tap 7 times))\n");

        System.out.println("3. Connect phone via USB cable\n");

        System.out.println("4. Verify ADB connection:");
        System.out.println("   $ adb devices");
        System.out.println("   (Should list your phone)\n");

        System.out.println("5. Run this app again:");
        System.out.println("   $ java -cp src net.lckx.phone.ReadFilesOnPhoneADB\n");

        System.out.println("📝 For more details, see ADB_SETUP.md");
    }
}
