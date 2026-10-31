package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads only the visible gallery page. Selected pictures are saved independently of evictable gallery data. */
final class ProfilePictures {
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(2 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    interface Callback { void ready(Bitmap bitmap); }
    static List<AvatarCatalog.Entry> catalogue(Context context) throws java.io.IOException {
        try (InputStream input = context.getAssets().open("profile-avatars.tsv")) {
            return AvatarCatalog.parse(new String(read(input, 128 * 1024), StandardCharsets.UTF_8));
        }
    }
    static void load(Context context, AvatarCatalog.Entry entry, java.util.function.BooleanSupplier current, Callback done) {
        Context app = context.getApplicationContext();
        Bitmap cached = CACHE.get(entry.id);
        if (cached != null) { done.ready(cached); return; }
        WORKER.execute(() -> {
            if (!current.getAsBoolean()) return;
            Bitmap bitmap = null;
            File directory = new File(app.getFilesDir(), "offline-artwork");
            try {
                byte[] bytes = OfflineArtwork.read(directory, entry.url);
                bitmap = decode(bytes);
                if (bitmap == null) {
                    if (bytes != null) OfflineArtwork.remove(directory, entry.url);
                    HttpURLConnection connection = (HttpURLConnection) new URL(entry.url).openConnection();
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(8000);
                    try (InputStream input = connection.getInputStream()) {
                        bytes = read(input, 512 * 1024);
                    } finally { connection.disconnect(); }
                    bitmap = decode(bytes);
                    if (bitmap != null) OfflineArtwork.save(directory, entry.url, bytes);
                }
            } catch (Exception unavailable) { /* Leave the existing photo and the generic preview intact. */ }
            if (bitmap != null) CACHE.put(entry.id, bitmap);
            Bitmap result = bitmap;
            new Handler(Looper.getMainLooper()).post(() -> { if (current.getAsBoolean()) done.ready(result); });
        });
    }
    private static byte[] read(InputStream input, int limit) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new java.io.IOException("Image too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static Bitmap decode(byte[] bytes) {
        if (bytes == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 2048 || bounds.outHeight > 2048) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= 256
                && bounds.outHeight / (options.inSampleSize * 2) >= 256) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null || Math.max(bitmap.getWidth(), bitmap.getHeight()) <= 256) return bitmap;
        float ratio = 256f / Math.max(bitmap.getWidth(), bitmap.getHeight());
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }
}
