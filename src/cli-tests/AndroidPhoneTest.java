package net.lckx.phone; /**
 * Test and demo class for net.lckx.phone.AndroidPhone ADB-based functionality.
 * Tests loading photos directly from Samsung phone via ADB and filtering by date.
 * This replaces the old PhoneTest which relied on /Volumes mounting.
 * User: louckxb, Date: 04/04/2026.
 */

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

public class AndroidPhoneTest {

    static void main() {
        try {
            System.out.println("╔════════════════════════════════════════════╗");
            System.out.println("║   net.lckx.phone.AndroidPhone ADB Test Suite              ║");
            System.out.println("╚════════════════════════════════════════════╝\n");

            // Test 1: Check ADB availability
            System.out.println("Test 1: Check ADB availability...");
            testAdbAvailability();

            System.out.println("\n" + "=".repeat(50) + "\n");

            // Test 2: Connect to phone and load photos
            System.out.println("Test 2: Connect to phone via ADB and load photos...");
            testPhoneConnection();

            System.out.println("\n" + "=".repeat(50) + "\n");

            // Test 3: Test date filtering by single date
            System.out.println("Test 3: Filter photos by single date...");
            testDateFiltering();

            System.out.println("\n" + "=".repeat(50) + "\n");

            // Test 4: Test date range filtering
            System.out.println("Test 4: Filter photos by date range...");
            testDateRangeFiltering();

            System.out.println("\n" + "=".repeat(50) + "\n");

            // Test 5: Test performance timing
            System.out.println("Test 5: Verify performance timing...");
            testPerformanceTiming();

        } catch (Exception e) {
            System.err.println("❌ Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test 1: Verify ADB is available on the system.
     */
    private static void testAdbAvailability() {
        try {
            AndroidPhone phone = new AndroidPhone();
            System.out.println("✓ ADB is available and working");
        } catch (IOException e) {
            System.out.println("❌ ADB not available: " + e.getMessage());
            System.out.println("\nTo fix this:");
            System.out.println("  1. Install Android SDK Platform Tools:");
            System.out.println("     brew install android-platform-tools");
            System.out.println("  2. Verify: adb version");
        }
    }

    /**
     * Test 2: Connect to phone and load photos.
     */
    private static void testPhoneConnection() {
        try {
            AndroidPhone phone = new AndroidPhone();
            System.out.println("🔗 Creating net.lckx.phone.AndroidPhone instance...");
            System.out.println("✓ Successfully created net.lckx.phone.AndroidPhone object\n");

            System.out.println("📱 Attempting to load photos from phone...");
            phone.loadPhotos();
            System.out.println("✓ Successfully connected and loaded photos\n");

            System.out.println("📊 Photo Statistics:");
            System.out.println("   Total photos: " + phone.getPhotoCount());
            System.out.println("   Load time: " + phone.formatTime(phone.getLoadTimeMs()));

            if (phone.getPhotoCount() > 0) {
                List<AndroidPhone.PhotoFile> allPhotos = phone.getAllPhotos();
                System.out.println("   First photo: " + allPhotos.get(0).filename());
                System.out.println("   Last photo: " + allPhotos.get(allPhotos.size() - 1).filename());
            }
        } catch (IOException e) {
            System.out.println("❌ Phone connection failed: " + e.getMessage());
            System.out.println("\nTroubleshooting:");
            System.out.println("  1. Ensure phone is connected via USB cable");
            System.out.println("  2. Unlock your phone");
            System.out.println("  3. Check if 'Allow USB Debugging?' popup is showing - tap Allow");
            System.out.println("  4. Verify with: adb devices");
        }
    }

    /**
     * Test 3: Filter photos by single date.
     */
    private static void testDateFiltering() {
        try {
            AndroidPhone phone = new AndroidPhone();
            phone.loadPhotos();

            if (phone.getPhotoCount() == 0) {
                System.out.println("⚠ No photos found to filter");
                return;
            }

            List<AndroidPhone.PhotoFile> allPhotos = phone.getAllPhotos();

            // Get a date from the actual photos
            LocalDate sampleDate = allPhotos.get(0).modifiedDateTime().toLocalDate();
            System.out.println("Testing with date: " + sampleDate + "\n");

            List<AndroidPhone.PhotoFile> filteredPhotos = phone.getPhotosByDate(sampleDate);
            System.out.println("✓ Found " + filteredPhotos.size() + " photos from " + sampleDate);
            System.out.println("  Search time: " + phone.formatTime(phone.getFilterTimeMs()));

            if (!filteredPhotos.isEmpty()) {
                System.out.println("\n  Sample photos:");
                filteredPhotos.stream().limit(3).forEach(p ->
                    System.out.println("    - " + p.filename() +
                        " (" + phone.formatFileSize(p.size()) + ")")
                );
                if (filteredPhotos.size() > 3) {
                    System.out.println("    ... and " + (filteredPhotos.size() - 3) + " more");
                }
            }
        } catch (IOException e) {
            System.out.println("❌ Date filtering test failed: " + e.getMessage());
        }
    }

    /**
     * Test 4: Filter photos by date range.
     */
    private static void testDateRangeFiltering() {
        try {
            AndroidPhone phone = new AndroidPhone();
            phone.loadPhotos();

            if (phone.getPhotoCount() == 0) {
                System.out.println("⚠ No photos found to filter");
                return;
            }

            List<AndroidPhone.PhotoFile> allPhotos = phone.getAllPhotos();
            LocalDate startDate = allPhotos.getFirst().modifiedDateTime().toLocalDate();
            LocalDate endDate = startDate.plusDays(7); // 7-day range

            System.out.println("Testing range: " + startDate + " till " + endDate + "\n");

            List<AndroidPhone.PhotoFile> rangePhotos = phone.getPhotosByDateRange(startDate, endDate);
            System.out.println("✓ Found " + rangePhotos.size() + " photos in date range");
            System.out.println("  Search time: " + phone.formatTime(phone.getFilterTimeMs()));

            if (!rangePhotos.isEmpty()) {
                System.out.println("\n  Date distribution in range:");
                rangePhotos.stream()
                    .map(p -> p.modifiedDateTime().toLocalDate())
                    .distinct()
                    .sorted()
                    .forEach(date -> {
                        long count = rangePhotos.stream()
                            .filter(p -> p.modifiedDateTime().toLocalDate().equals(date))
                            .count();
                        System.out.println("    " + date + ": " + count + " photos");
                    });
            }
        } catch (IOException e) {
            System.out.println("❌ Date range filtering test failed: " + e.getMessage());
        }
    }

    /**
     * Test 5: Verify performance timing is accurate.
     */
    private static void testPerformanceTiming() {
        try {
            AndroidPhone phone = new AndroidPhone();

            // Measure load time
            long startOverall = System.currentTimeMillis();
            phone.loadPhotos();
            long totalLoadTime = System.currentTimeMillis() - startOverall;

            System.out.println("✓ Load time recorded: " + phone.formatTime(phone.getLoadTimeMs()));
            System.out.println("  Total elapsed time: " + phone.formatTime(totalLoadTime));
            System.out.println("  Difference: " + (totalLoadTime - phone.getLoadTimeMs()) + "ms (expected ~0-50ms)\n");

            if (phone.getPhotoCount() > 0) {
                List<AndroidPhone.PhotoFile> allPhotos = phone.getAllPhotos();
                LocalDate testDate = allPhotos.get(0).modifiedDateTime().toLocalDate();

                // Measure filter time
                phone.getPhotosByDate(testDate);
                long filterTime = phone.getFilterTimeMs();

                System.out.println("✓ Filter time recorded: " + phone.formatTime(filterTime));
                System.out.println("  (Should be < 100ms for cached filtering)\n");

                if (filterTime < 100) {
                    System.out.println("✓ Performance is excellent (instant filtering)");
                } else if (filterTime < 1000) {
                    System.out.println("✓ Performance is good (sub-second filtering)");
                } else {
                    System.out.println("⚠ Performance is slower than expected");
                }
            }
        } catch (IOException e) {
            System.out.println("❌ Performance timing test failed: " + e.getMessage());
        }
    }
}
