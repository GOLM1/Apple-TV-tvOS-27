package com.gathin.tvhome;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** Small durable artwork store; independent of HTTP freshness and Android's disposable cache directory. */
final class OfflineArtwork {
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    private OfflineArtwork() {}

    private static File file(File directory, String uri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(uri.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return new File(directory, name + ".img");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static synchronized byte[] read(File directory, String uri) {
        File file = file(directory, uri);
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_IMAGE_BYTES) return null;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            file.setLastModified(System.currentTimeMillis());
            return bytes;
        } catch (IOException unavailable) { return null; }
    }

    static synchronized void remove(File directory, String uri) { file(directory, uri).delete(); }

    static synchronized void cleanup(File directory) {
        new File(directory, "artwork.part").delete();
        trim(directory, MAX_BYTES);
    }

    static synchronized void save(File directory, String uri, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) return;
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        File target = file(directory, uri);
        File temporary = new File(directory, "artwork.part");
        try {
            Files.write(temporary.toPath(), bytes);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            target.setLastModified(System.currentTimeMillis());
            trim(directory, MAX_BYTES);
        } catch (IOException unavailable) {
            // Failed persistence must not prevent showing a downloaded image.
        } finally { temporary.delete(); }
    }

    static synchronized void trim(File directory, long limit) {
        File[] files = directory.listFiles(f -> f.isFile() && f.getName().endsWith(".img"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total <= limit) break;
            long bytes = file.length();
            if (file.delete()) total -= bytes;
        }
    }
}
