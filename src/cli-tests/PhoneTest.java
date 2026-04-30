/**
 * Test and demo class for Phone photo gallery functionality.
 * Shows how to load photos from Samsung phone and filter by date.
 * Integrates with OneDrive upload for batch uploading selected photos.
 * User: louckxb, Date: 03/04/2026.
 */

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Scanner;

public class PhoneTest {

    public static void main(String[] args) {
        try {
            System.out.println("=== Samsung Phone Gallery Reader ===\n");

            // Example 1: Auto-detect phone and load all photos
            System.out.println("Example 1: Auto-detecting phone connection...");
            demonstrateAutoDetection();

            System.out.println("\n" + "=".repeat(50) + "\n");

            // Example 2: Load photos and filter by date
            System.out.println("Example 2: Filter photos by date range...");
            demonstrateDateFiltering();

            System.out.println("\n" + "=".repeat(50) + "\n");

            // Example 3: Interactive mode for selecting and uploading photos
            System.out.println("Example 3: Interactive photo selection and upload...");
            demonstrateInteractiveMode();

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Demo: Auto-detect Samsung phone and load all photos.
     */
    private static void demonstrateAutoDetection() throws IOException {
        try {
            Phone phone = new Phone();
            phone.loadPhotos();
            phone.printPhotoSummary();

            System.out.println("\nTotal photos on phone: " + phone.getPhotoCount());
            System.out.println("Photos are sorted by date (oldest to newest).");
        } catch (IOException e) {
            System.out.println("⚠ Phone not detected: " + e.getMessage());
            System.out.println("Ensure your Samsung phone is connected and unlocked.");
            System.out.println("Common mount paths checked:");
            System.out.println("  - /Volumes/Galaxy/DCIM/Camera");
            System.out.println("  - /Volumes/Phone/DCIM/Camera");
            System.out.println("  - /Volumes/SD Card/DCIM/Camera");
        }
    }

    /**
     * Demo: Load photos and filter by various date criteria.
     */
    private static void demonstrateDateFiltering() throws IOException {
        try {
            Phone phone = new Phone();
            phone.loadPhotos();

            // Filter by specific date
            System.out.println("\n1. Photos from March 15, 2026:");
            List<Phone.PhotoFile> specificDate = phone.getPhotosByDate(LocalDate.of(2026, 3, 15));
            printPhotos(specificDate);

            // Filter by year and month
            System.out.println("\n2. All photos from March 2026:");
            List<Phone.PhotoFile> marchPhotos = phone.getPhotosByYearMonth(2026, 3);
            printPhotos(marchPhotos.stream().limit(5).toList()); // Show first 5
            if (marchPhotos.size() > 5) {
                System.out.println("... and " + (marchPhotos.size() - 5) + " more photos");
            }

            // Filter by date range
            System.out.println("\n3. Photos from March 10-20, 2026:");
            List<Phone.PhotoFile> rangePhotos = phone.getPhotosByDateRange(
                    LocalDate.of(2026, 3, 10),
                    LocalDate.of(2026, 3, 20)
            );
            printPhotos(rangePhotos.stream().limit(5).toList());
            if (rangePhotos.size() > 5) {
                System.out.println("... and " + (rangePhotos.size() - 5) + " more photos");
            }

            // Filter by year
            System.out.println("\n4. Total photos from 2026: " + phone.getPhotosByYear(2026).size());

        } catch (IOException e) {
            System.out.println("⚠ Could not load photos: " + e.getMessage());
        }
    }

    /**
     * Demo: Interactive mode to select photos and upload to OneDrive.
     */
    private static void demonstrateInteractiveMode() {
        try {
            Phone phone = new Phone();
            phone.loadPhotos();

            Scanner scanner = new Scanner(System.in);

            while (true) {
                System.out.println("\n=== Interactive Photo Selection ===");
                System.out.println("1. Filter by date");
                System.out.println("2. Filter by year/month");
                System.out.println("3. Filter by date range");
                System.out.println("4. View all photos");
                System.out.println("5. Exit");
                System.out.print("Select option (1-5): ");

                String choice = scanner.nextLine().trim();
                List<Phone.PhotoFile> selectedPhotos = null;

                switch (choice) {
                    case "1" -> selectedPhotos = filterBySpecificDate(scanner, phone);
                    case "2" -> selectedPhotos = filterByYearMonth(scanner, phone);
                    case "3" -> selectedPhotos = filterByDateRange(scanner, phone);
                    case "4" -> selectedPhotos = phone.getAllPhotos();
                    case "5" -> {
                        System.out.println("Goodbye!");
                        scanner.close();
                        return;
                    }
                    default -> System.out.println("Invalid option. Try again.");
                }

                if (selectedPhotos != null && !selectedPhotos.isEmpty()) {
                    displayAndUpload(scanner, selectedPhotos);
                } else if (selectedPhotos != null) {
                    System.out.println("No photos found matching your criteria.");
                }
            }

        } catch (IOException e) {
            System.out.println("⚠ Error: " + e.getMessage());
        }
    }

    /**
     * Helper: Filter by specific date.
     */
    private static List<Phone.PhotoFile> filterBySpecificDate(Scanner scanner, Phone phone) {
        System.out.print("Enter date (YYYY-MM-DD): ");
        try {
            LocalDate date = LocalDate.parse(scanner.nextLine().trim());
            List<Phone.PhotoFile> photos = phone.getPhotosByDate(date);
            System.out.println("Found " + photos.size() + " photos on " + date);
            return photos;
        } catch (Exception e) {
            System.out.println("Invalid date format. Use YYYY-MM-DD");
            return null;
        }
    }

    /**
     * Helper: Filter by year and month.
     */
    private static List<Phone.PhotoFile> filterByYearMonth(Scanner scanner, Phone phone) {
        try {
            System.out.print("Enter year (e.g., 2026): ");
            int year = Integer.parseInt(scanner.nextLine().trim());
            System.out.print("Enter month (1-12): ");
            int month = Integer.parseInt(scanner.nextLine().trim());

            if (month < 1 || month > 12) {
                System.out.println("Invalid month (1-12)");
                return null;
            }

            List<Phone.PhotoFile> photos = phone.getPhotosByYearMonth(year, month);
            System.out.println("Found " + photos.size() + " photos in " + year + "-" +
                    String.format("%02d", month));
            return photos;
        } catch (NumberFormatException e) {
            System.out.println("Invalid input. Please enter numbers.");
            return null;
        }
    }

    /**
     * Helper: Filter by date range.
     */
    private static List<Phone.PhotoFile> filterByDateRange(Scanner scanner, Phone phone) {
        try {
            System.out.print("Enter start date (YYYY-MM-DD): ");
            LocalDate startDate = LocalDate.parse(scanner.nextLine().trim());
            System.out.print("Enter end date (YYYY-MM-DD): ");
            LocalDate endDate = LocalDate.parse(scanner.nextLine().trim());

            if (startDate.isAfter(endDate)) {
                System.out.println("Start date must be before end date.");
                return null;
            }

            List<Phone.PhotoFile> photos = phone.getPhotosByDateRange(startDate, endDate);
            System.out.println("Found " + photos.size() + " photos between " + startDate +
                    " and " + endDate);
            return photos;
        } catch (Exception e) {
            System.out.println("Invalid date format. Use YYYY-MM-DD");
            return null;
        }
    }

    /**
     * Helper: Display selected photos and offer upload option.
     */
    private static void displayAndUpload(Scanner scanner, List<Phone.PhotoFile> photos) {
        System.out.println("\n=== Selected Photos (" + photos.size() + " total) ===");

        // Show first 10
        for (int i = 0; i < Math.min(10, photos.size()); i++) {
            System.out.println((i + 1) + ". " + photos.get(i));
        }

        if (photos.size() > 10) {
            System.out.println("... and " + (photos.size() - 10) + " more photos");
        }

        System.out.print("\nUpload these photos to OneDrive? (yes/no): ");
        String response = scanner.nextLine().trim().toLowerCase();

        if (response.equals("yes") || response.equals("y")) {
            System.out.print("Enter OneDrive folder name (default: 'Phone Gallery'): ");
            String folderName = scanner.nextLine().trim();
            if (folderName.isEmpty()) {
                folderName = "Phone Gallery";
            }

            uploadPhotosToOneDrive(photos, folderName);
        }
    }

    /**
     * Helper: Upload selected photos to OneDrive.
     * Note: Requires valid OAuth token at ~/.onedrive-rw-token (run Upload.java first)
     */
    private static void uploadPhotosToOneDrive(List<Phone.PhotoFile> photos, String folderName) {
        System.out.println("\n=== Upload to OneDrive ===");
        System.out.println("⚠️  Note: This feature requires running Upload.java first to authenticate.");
        System.out.println("    Once authenticated, your token will be cached at ~/.onedrive-rw-token");
        System.out.println("\nDestination folder: " + folderName);
        System.out.println("Photos to upload: " + photos.size());
        System.out.println("\nTo integrate with Upload.java, use the following approach:");

        for (int i = 0; i < Math.min(3, photos.size()); i++) {
            Phone.PhotoFile photo = photos.get(i);
            System.out.printf("  [%d] %s%n", i + 1, photo.getFilename());
        }
        if (photos.size() > 3) {
            System.out.println("  ... and " + (photos.size() - 3) + " more photos");
        }

        System.out.println("\n📝 Recommended workflow:");
        System.out.println("  1. Run: java -cp src Upload");
        System.out.println("  2. Complete device code authentication");
        System.out.println("  3. Then use PhoneTest to select and upload photos");
        System.out.println("\nFor programmatic uploads, see Upload.java documentation.");
    }

    /**
     * Helper: Pretty print photo list.
     */
    private static void printPhotos(List<Phone.PhotoFile> photos) {
        if (photos.isEmpty()) {
            System.out.println("  (no photos found)");
            return;
        }
        for (Phone.PhotoFile photo : photos) {
            System.out.println("  " + photo);
        }
    }
}
