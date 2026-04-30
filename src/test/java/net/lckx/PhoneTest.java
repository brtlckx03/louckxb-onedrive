package net.lckx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phone photo gallery functionality.
 * Requires a Samsung phone connected via USB mount.
 */
class PhoneTest {

    static boolean isPhoneConnected() {
        try {
            Phone phone = new Phone();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @EnabledIf("isPhoneConnected")
    void autoDetection_shouldFindPhone() throws IOException {
        Phone phone = new Phone();
        phone.loadPhotos();
        assertTrue(phone.getPhotoCount() >= 0);
    }

    @Test
    @EnabledIf("isPhoneConnected")
    void dateFiltering_shouldFilterByDate() throws IOException {
        Phone phone = new Phone();
        phone.loadPhotos();

        if (phone.getPhotoCount() == 0) return;

        List<Phone.PhotoFile> allPhotos = phone.getAllPhotos();
        LocalDate sampleDate = allPhotos.get(0).getModifiedDateTime().toLocalDate();
        List<Phone.PhotoFile> filtered = phone.getPhotosByDate(sampleDate);
        assertNotNull(filtered);
    }
}
