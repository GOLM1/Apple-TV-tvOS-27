package com.gathin.tvhome;

import java.io.File;
import java.io.RandomAccessFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PreviewCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File file(File directory, String name, long bytes, long modified) throws Exception {
        File file = new File(directory, name);
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.setLength(bytes); }
        assertTrue(file.setLastModified(modified));
        return file;
    }

    @Test public void evictsOldestVideoAndAudioTogether() throws Exception {
        File directory = temporary.newFolder();
        File oldVideo = file(directory, "old.mp4", 30, 1000);
        File oldAudio = file(directory, "old.m4a", 20, 2000);
        File recent = file(directory, "recent.mp4", 40, 3000);
        PreviewCache.trim(directory, 50);
        assertFalse(oldVideo.exists());
        assertFalse(oldAudio.exists());
        assertTrue(recent.exists());
    }

    @Test public void preservesPlayingAndNewlyResolvedFiles() throws Exception {
        File directory = temporary.newFolder();
        File playing = file(directory, "playing.mp4", 20, 1000);
        File unused = file(directory, "unused.mp4", 30, 2000);
        File next = file(directory, "next.mp4", 20, 3000);
        PreviewCache.trim(directory, 40, playing, next);
        assertTrue(playing.exists());
        assertTrue(next.exists());
        assertFalse(unused.exists());
    }

    @Test public void startupRemovesAbandonedWorkWithoutRemovingCompletedMedia() throws Exception {
        File directory = temporary.newFolder();
        File video = file(directory, "ready.mp4", 20, 1000);
        for (String suffix : new String[]{".part", ".tmp", ".mux"}) {
            file(directory, "abandoned" + suffix, 10, 1000);
        }
        File unrelated = file(directory, "notes.txt", 2, 1000);
        PreviewCache.cleanup(directory);
        assertTrue(video.exists());
        assertTrue(unrelated.exists());
        assertEquals(2, directory.listFiles().length);
    }

    @Test public void inProgressFilesCountButAreNotEvicted() throws Exception {
        File directory = temporary.newFolder();
        File work = file(directory, "download.part", 30, 1000);
        File unused = file(directory, "unused.mp4", 40, 2000);
        PreviewCache.trim(directory, 50);
        assertTrue(work.exists());
        assertFalse(unused.exists());
    }

    @Test public void cacheHitsBecomeRecentAndMissingDirectoryIsSafe() throws Exception {
        File directory = temporary.newFolder();
        File reused = file(directory, "reused.mp4", 20, 1000);
        File unused = file(directory, "unused.mp4", 20, 2000);
        PreviewCache.touch(reused);
        PreviewCache.trim(directory, 20);
        assertTrue(reused.exists());
        assertFalse(unused.exists());
        PreviewCache.cleanup(new File(directory, "missing"));
        PreviewCache.touch(null);
    }
}
