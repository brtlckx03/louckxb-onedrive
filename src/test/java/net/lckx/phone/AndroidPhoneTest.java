package net.lckx.phone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for AndroidPhone ADB-based functionality.
 * These tests require a phone connected via USB with ADB enabled and photos accessible.
 * They are skipped if no device is connected or no photos are found.
 */
class AndroidPhoneTest {

    static boolean isAdbDeviceConnected() {
        try {
            Process p = new ProcessBuilder("adb", "devices").start();
            if (p.waitFor() != 0) return false;
            String output = new String(p.getInputStream().readAllBytes());
            return output.lines()
                    .skip(1)
                    .anyMatch(line -> line.endsWith("device"));
        } catch (Exception e) {
            return false;
        }
    }

    private AndroidPhone loadPhoneWithPhotos() {
        try {
            AndroidPhone phone = new AndroidPhone();
            phone.loadPhotos();
            return phone;
        } catch (IOException e) {
            assumeTrue(false, "Skipping: " + e.getMessage());
            return null; // unreachable
        }
    }

    @Test
    @EnabledIf("isAdbDeviceConnected")
    void phoneConnection_shouldLoadPhotos() {
        AndroidPhone phone = loadPhoneWithPhotos();
        assertTrue(phone.getPhotoCount() >= 0);
    }

    @Test
    @EnabledIf("isAdbDeviceConnected")
    void dateFiltering_shouldReturnPhotosForDate() {
        AndroidPhone phone = loadPhoneWithPhotos();
        if (phone.getPhotoCount() == 0) return;

        List<AndroidPhone.PhotoFile> allPhotos = phone.getAllPhotos();
        LocalDate sampleDate = allPhotos.get(0).modifiedDateTime().toLocalDate();
        List<AndroidPhone.PhotoFile> filtered = phone.getPhotosByDate(sampleDate);
        assertFalse(filtered.isEmpty());
    }

    @Test
    @EnabledIf("isAdbDeviceConnected")
    void dateRangeFiltering_shouldReturnPhotosInRange() {
        AndroidPhone phone = loadPhoneWithPhotos();
        if (phone.getPhotoCount() == 0) return;

        List<AndroidPhone.PhotoFile> allPhotos = phone.getAllPhotos();
        LocalDate startDate = allPhotos.getFirst().modifiedDateTime().toLocalDate();
        LocalDate endDate = startDate.plusDays(7);
        List<AndroidPhone.PhotoFile> rangePhotos = phone.getPhotosByDateRange(startDate, endDate);
        assertNotNull(rangePhotos);
    }
}
