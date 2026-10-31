package com.gathin.tvhome;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/** Disk-only preview housekeeping, called on the shared preview worker, never the UI thread. */
final class PreviewCache {
    static final long MAX_BYTES = 64L * 1024 * 1024;

    private PreviewCache() {}

    /** No writer is active when the shared worker starts a new owner's cleanup. */
    static void cleanup(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && (name.endsWith(".part") || name.endsWith(".tmp") || name.endsWith(".mux"))) {
                file.delete();
            }
        }
        trim(directory, MAX_BYTES);
    }

    static void touch(File file) {
        if (file != null && file.isFile()) file.setLastModified(System.currentTimeMillis());
    }

    /** Working files count towards the budget, but only completed, unused previews may be evicted. */
    static void trim(File directory, long limit, File... protectedFiles) {
        File[] files = directory.listFiles();
        if (files == null) return;
        long total = 0;
        for (File file : files) if (file.isFile()) total += file.length();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (total <= limit) break;
            String name = file.getName();
            if (!file.isFile() || !(name.endsWith(".mp4") || name.endsWith(".m4a"))) continue;
            boolean protectedFile = false;
            for (File keep : protectedFiles) if (file.equals(keep)) protectedFile = true;
            if (protectedFile) continue;
            long bytes = file.length();
            if (file.delete()) total -= bytes;
        }
    }
}
