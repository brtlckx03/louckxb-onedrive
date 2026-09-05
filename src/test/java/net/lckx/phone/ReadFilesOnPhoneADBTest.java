package net.lckx.phone;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadFilesOnPhoneADBTest {

    @Test
    void datePrefixedFilename_movesWhatsAppDateToFront() {
        assertEquals("20260701-IMG-WA0002.jpg",
                ReadFilesOnPhoneADB.datePrefixedFilename("IMG-20260701-WA0002.jpg"));
    }

    @Test
    void datePrefixedFilename_movesScreenshotDateAndTimeToFront() {
        assertEquals("20260623_154617_Screenshot_Connect.jpg",
                ReadFilesOnPhoneADB.datePrefixedFilename("Screenshot_20260623_154617_Connect.jpg"));
        assertEquals("20260623_093944_Screenshot_MAPSME.jpg",
                ReadFilesOnPhoneADB.datePrefixedFilename("Screenshot_20260623_093944_MAPSME.jpg"));
    }

    @Test
    void datePrefixedFilename_leavesAlreadyPrefixedNameUnchanged() {
        assertEquals("20260701_IMG.jpg",
                ReadFilesOnPhoneADB.datePrefixedFilename("20260701_IMG.jpg"));
    }

    @Test
    void datePrefixedFilename_leavesNameWithoutDateUnchanged() {
        assertEquals("random_photo.jpg",
                ReadFilesOnPhoneADB.datePrefixedFilename("random_photo.jpg"));
    }

    @Test
    void weekFolderName_returnsMondayToSundayRange() {
        // 2026-06-23 is a Tuesday -> week is Mon 2026-06-22 .. Sun 2026-06-28
        assertEquals("20260622-20260628",
                ReadFilesOnPhoneADB.weekFolderName(LocalDate.of(2026, 6, 23)));
        // Monday itself
        assertEquals("20260601-20260607",
                ReadFilesOnPhoneADB.weekFolderName(LocalDate.of(2026, 6, 1)));
        // Sunday -> stays in that same week (Mon 15 .. Sun 21)
        assertEquals("20260615-20260621",
                ReadFilesOnPhoneADB.weekFolderName(LocalDate.of(2026, 6, 21)));
    }
}
