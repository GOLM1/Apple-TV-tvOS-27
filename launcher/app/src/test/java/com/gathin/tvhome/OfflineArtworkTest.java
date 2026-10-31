package com.gathin.tvhome;

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class OfflineArtworkTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void staleArtworkSurvivesWithoutAnHttpExpiry() throws Exception {
        File directory = temporary.newFolder();
        byte[] bytes = new byte[]{1, 2, 3};
        OfflineArtwork.save(directory, "https://example.com/image", bytes);
        for (File file : directory.listFiles()) assertTrue(file.setLastModified(1000));
        assertArrayEquals(bytes, OfflineArtwork.read(directory, "https://example.com/image"));
        assertNull(OfflineArtwork.read(directory, "https://example.com/other"));
    }

    @Test public void budgetEvictsOldArtworkAndPreservesRecentReads() throws Exception {
        File directory = temporary.newFolder();
        OfflineArtwork.save(directory, "older", new byte[30]);
        OfflineArtwork.save(directory, "recent", new byte[30]);
        for (File file : directory.listFiles()) assertTrue(file.setLastModified(1000));
        OfflineArtwork.read(directory, "recent");
        OfflineArtwork.trim(directory, 30);
        assertNull(OfflineArtwork.read(directory, "older"));
        assertNotNull(OfflineArtwork.read(directory, "recent"));
    }

    @Test public void failedReplacementKeepsTheLastSuccessfulImage() throws Exception {
        File directory = temporary.newFolder();
        byte[] original = new byte[]{1, 2, 3};
        OfflineArtwork.save(directory, "image", original);
        File blocked = new File(directory, "artwork.part");
        assertTrue(blocked.mkdir());
        OfflineArtwork.save(directory, "image", new byte[]{4, 5, 6});
        assertArrayEquals(original, OfflineArtwork.read(directory, "image"));
    }

    @Test public void interruptedWorkIsRemovedWithoutLosingSavedArtwork() throws Exception {
        File directory = temporary.newFolder();
        OfflineArtwork.save(directory, "image", new byte[]{1, 2, 3});
        File partial = new File(directory, "artwork.part");
        Files.write(partial.toPath(), new byte[]{4, 5});
        OfflineArtwork.cleanup(directory);
        assertFalse(partial.exists());
        assertNotNull(OfflineArtwork.read(directory, "image"));
        OfflineArtwork.remove(directory, "image");
        assertNull(OfflineArtwork.read(directory, "image"));
    }

    @Test public void oversizedOrEmptyDownloadsCannotFillThePersistentStore() throws Exception {
        File directory = temporary.newFolder();
        OfflineArtwork.save(directory, "huge", new byte[OfflineArtwork.MAX_IMAGE_BYTES + 1]);
        OfflineArtwork.save(directory, "empty", new byte[0]);
        assertEquals(0, directory.listFiles().length);
    }
}
