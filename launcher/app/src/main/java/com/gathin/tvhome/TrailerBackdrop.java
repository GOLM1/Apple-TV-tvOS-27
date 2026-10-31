package com.gathin.tvhome;

import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import org.json.JSONObject;

/** Background previews from explicitly public short trailer assets, with audio and stepped fade-out. */
final class TrailerBackdrop implements TextureView.SurfaceTextureListener {
    private final View poster;
    /** finished: no preview after all (unavailable, failed); ended: it played to its end or its time limit. */
    private final Runnable finished, ended, active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextureView view;
    private MediaPlayer player;
    /** YouTube's sound, a separate stream beside its video-only picture (pendingAudio), started with the picture. */
    private MediaPlayer audio;
    private volatile String pendingAudio;
    private Surface surface;
    private final TrailerRequests requests = new TrailerRequests();
    private boolean busy, rendering, buffering;
    private final PreviewClock previewClock = new PreviewClock();
    private int playbackLimit;
    private int bufferedPercent;
    private long playbackPosition, playbackProgressAt;
    private boolean muted;
    private float outputGain = 1f;
    void setMuted(boolean muted) {
        this.muted = muted;
        float gain = muted ? 0f : outputGain;
        try {
            if (player != null) player.setVolume(gain, gain);
            if (audio != null) audio.setVolume(gain, gain);
        } catch (IllegalStateException ignored) { /* a player is being released */ }
    }
    private Runnable progressChanged = () -> {};
    void onProgress(Runnable listener) { progressChanged = listener; }
    float progress() { return rendering ? previewClock.fraction(android.os.SystemClock.uptimeMillis()) : -1f; }
    boolean progressRunning() { return rendering && previewClock.running(); }
    private volatile String pendingUrl;
    /** A small frame of the video for the glass (onSurfaceTextureUpdated), reused, and when it was last taken. */
    private android.graphics.Bitmap glassFrame;
    private long lastGlassFrame;
    /** A glass frame every other video frame (about 16 a second): the dock still moves with the picture, at half the cost. */
    private static final long GLASS_FRAME_MS = 60;
    /** A slide waits this long before its trailer loads, so paging past it fetches nothing. */
    private static final long SHOW_DELAY_MS = 1800;
    private static final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final android.util.LruCache<String, String> URLS = new android.util.LruCache<>(32);
    TrailerBackdrop(View poster, Runnable finished, Runnable ended, Runnable active) {
        this.poster = poster; this.finished = finished; this.ended = ended; this.active = active;
        java.io.File directory = new java.io.File(poster.getContext().getCacheDir(), "trailer-previews");
        worker.execute(() -> PreviewCache.cleanup(directory));
    }
    boolean busy() { return busy; }
    /** The trailer's picture now, at half size (it only fades out), or null when none is showing. */
    android.graphics.Bitmap frame() {
        return rendering && view != null && view.isAvailable() && view.getWidth() > 0
                ? view.getBitmap(view.getWidth() / 2, view.getHeight() / 2) : null;
    }
    /**
     * Fetch the next slide's trailer into the caches while this one shows, so it starts without the resolve and the
     * sound download. Queued after show's own work (posted later than its delay), dropped when the slide changes.
     */
    void prewarm(String page, String[] youtubeIds) {
        if (page == null && youtubeIds.length == 0) return;
        String next = page + "|" + String.join(",", youtubeIds);
        if (requests.prewarming(next)) return;
        int request = requests.prewarm(next);
        main.postDelayed(() -> worker.execute(() -> {
            if (!stale(request)) resolve(page, youtubeIds, request);
        }), SHOW_DELAY_MS + 200);
    }

    void show(String page, String[] youtubeIds) {
        // Showing the slide being prewarmed lets that work finish: this slide's own task, queued after it, then finds
        // it on the disk.
        requests.show(page + "|" + String.join(",", youtubeIds));
        stop();
        android.util.Log.i("TrailerBackdrop", "Preview requested, public page=" + (page != null));
        if (page == null && youtubeIds.length == 0) return;
        busy = true;
        int request = requests.current();
        loadingProgress(request);
        active.run(); // Pause the poster's timer immediately, before the first frame is ready.
        main.postDelayed(() -> {
            if (stale(request)) return;
            worker.execute(() -> {
                if (stale(request)) return;
                loadingProgress(request);
                String[] resolved = resolve(page, youtubeIds, request);
                String ready = resolved[0], readyAudio = resolved[1];
                main.post(() -> {
                    if (stale(request)) return;
                    android.util.Log.i("TrailerBackdrop", "Compatible stream=" + (ready != null));
                    if (ready == null) { stop(); finished.run(); return; }
                    loadingProgress(request);
                    pendingUrl = ready;
                    pendingAudio = readyAudio;
                    android.util.Log.i("TrailerBackdrop", "Separate audio=" + (readyAudio != null));
                    ensureView();
                    view.setAlpha(0.001f);
                    view.setVisibility(View.VISIBLE);
                    if (view.isAvailable()) play(request);
                });
            });
        }, SHOW_DELAY_MS);
        main.postDelayed(() -> checkLoading(request), 1000);
    }

    private void loadingProgress(int request) {
        requests.progress(request, android.os.SystemClock.uptimeMillis());
    }

    /** Slow transfers keep their slide; only a full 30 seconds without progress is a timeout. */
    private void checkLoading(int request) {
        if (stale(request) || rendering) return;
        if (requests.stalled(android.os.SystemClock.uptimeMillis())) {
            android.util.Log.w("TrailerBackdrop", "Preview preparation stalled");
            stop();
            finished.run();
        } else main.postDelayed(() -> checkLoading(request), 1000);
    }
    /**
     * The playable picture (and YouTube's separate sound) for a slide: {url, audio}, either null. Network and muxing,
     * so on the worker; what it fetches is cached (URLS, the resolver, localAudio), which is what prewarm relies on.
     */
    private String[] resolve(String page, String[] youtubeIds, int request) {
        String[] result = null;
        try {
            result = resolveSource(page, youtubeIds, request);
            return result;
        } finally {
            java.io.File picture = cacheFile(result == null ? null : result[0]);
            java.io.File sound = cacheFile(result == null ? null : result[1]);
            PreviewCache.touch(picture);
            PreviewCache.touch(sound);
            PreviewCache.trim(new java.io.File(poster.getContext().getCacheDir(), "trailer-previews"),
                    PreviewCache.MAX_BYTES, picture, sound, cacheFile(pendingUrl), cacheFile(pendingAudio));
        }
    }

    private static java.io.File cacheFile(String path) {
        return path == null || path.startsWith("https://") ? null : new java.io.File(path);
    }

    private String[] resolveSource(String page, String[] youtubeIds, int request) {
        loadingProgress(request);
        String url = page == null ? null : URLS.get(page);
        if (url != null && !new java.io.File(url).isFile()) url = null;
        try {
            if (url == null && page != null) {
                String metrics = TrailerSource.previewMetrics(get(page, request));
                android.util.Log.i("TrailerBackdrop", "Short preview metadata=" + (metrics != null));
                if (metrics != null) {
                    String masterUrl = new JSONObject(metrics).optString("url");
                    if (masterUrl.startsWith("https://play-edge.itunes.apple.com/")) {
                        String master = get(masterUrl, request);
                        // The page identifies a short Extra; variant() accepts only public VideoPreview assets.
                        String videoUrl = TrailerSource.variant(master);
                        String audioUrl = TrailerSource.audioVariant(master);
                        if (videoUrl != null) {
                            url = compatiblePreview(videoUrl, audioUrl, request);
                        }
                    }
                }
                if (url != null) URLS.put(page, url);
            }
        } catch (Exception e) { android.util.Log.w("TrailerBackdrop", "Preview unavailable: " + e.getClass().getSimpleName()); }
        String audioUrl = null;
        if (url == null && !stale(request)) {
            // A trailer already joined for this slide: straight from the disk, no resolving.
            java.io.File[] joinedBefore = new java.io.File(poster.getContext().getCacheDir(), "trailer-previews").listFiles();
            for (String id : youtubeIds) {
                String prefix = "yt-" + safeId(id) + "-";
                for (java.io.File f : joinedBefore == null ? new java.io.File[0] : joinedBefore) {
                    if (f.getName().startsWith(prefix) && f.getName().substring(prefix.length()).matches("[0-9]+p-av[.]mp4") && f.length() > 0) {
                        return new String[]{f.getAbsolutePath(), null};
                    }
                }
            }
            // TMDB lists several; the first is often an old low-resolution upload. Take the sharpest of them all.
            android.os.Bundle best = null;
            String bestId = null;
            for (String id : youtubeIds) {
                if (stale(request)) break;
                try {
                    loadingProgress(request);
                    android.os.Bundle result = poster.getContext().getContentResolver().call(
                            android.net.Uri.parse("content://com.gathin.tvhome.trailerresolver"), "resolve", id, null);
                    loadingProgress(request);
                    String stream = result == null || !"ready".equals(result.getString("status")) ? null : result.getString("url");
                    if (stream == null || !stream.startsWith("https://")) continue;
                    if (best == null || result.getInt("height") > best.getInt("height")) { best = result; bestId = id; }
                    if (best.getInt("height") >= 1080) break;   // nothing sharper on offer
                } catch (RuntimeException error) {
                    android.util.Log.w("TrailerBackdrop", "Optional resolver unavailable: " + error.getClass().getSimpleName());
                    break;
                }
            }
            if (best != null && !stale(request)) {
                String stream = best.getString("url");
                url = stream;
                String sound = best.getString("audio");
                if (sound != null && sound.startsWith("https://")) {
                    // One file, one player: two players drift (the TV delays sound ~0.5 s).
                    String flat = localAudio(sound, bestId, request);
                    String joined = flat == null ? null : joinYouTube(stream, flat, bestId + "-" + best.getInt("height") + "p", request);
                    if (joined != null) url = joined;
                    else audioUrl = flat;   // fall back to the picture streamed, sound beside it
                }
                android.util.Log.i("TrailerBackdrop", "YouTube preview resolved by separate helper, " + best.getInt("height") + "p");
            }
        }
        return new String[]{url, audioUrl};
    }
    private void ensureView() {
        if (view != null) return;
        view = new TextureView(poster.getContext());
        view.setOpaque(false); view.setAlpha(0.001f);
        view.setFocusable(false); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setSurfaceTextureListener(this);
        ViewGroup root = (ViewGroup) poster.getParent();
        root.addView(view, root.indexOfChild(poster) + 1, new ViewGroup.LayoutParams(-1, -1));
    }
    private void play(int request) {
        if (pendingUrl == null || player != null || view.getSurfaceTexture() == null) return;
        try {
            android.util.Log.i("TrailerBackdrop", "Preparing public video surface");
            player = new MediaPlayer();
            MediaPlayer current = player;
            surface = new Surface(view.getSurfaceTexture());
            current.setSurface(surface); current.setVolume(0f, 0f);
            current.setDataSource(pendingUrl);
            current.setOnPreparedListener(p -> { if (!stale(request)) p.start(); });
            bufferedPercent = -1;
            current.setOnBufferingUpdateListener((p, percent) -> {
                if (!stale(request) && percent > bufferedPercent) {
                    bufferedPercent = percent;
                    loadingProgress(request);
                }
            });
            current.setOnVideoSizeChangedListener((p, w, h) -> crop(w, h));
            current.setOnInfoListener((p, what, extra) -> {
                if (!stale(request) && (what == MediaPlayer.MEDIA_INFO_BUFFERING_START
                        || what == MediaPlayer.MEDIA_INFO_BUFFERING_END)) {
                    buffering = what == MediaPlayer.MEDIA_INFO_BUFFERING_START;
                    if (rendering) samplePlayback(current, request, false);
                }
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !stale(request)) {
                    rendering = true;
                    view.animate().alpha(1f).setDuration(600).start();
                    outputGain = 1f;
                    current.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
                    startAudio(current, request);
                    android.util.Log.i("TrailerBackdrop", "Public trailer playing with audio");
                    int durationMs = 30000;
                    try {
                        int d = current.getDuration();
                        if (d > 0 && d < durationMs) durationMs = d;
                    } catch (Exception ignored) {}
                    playbackLimit = durationMs;
                    playbackPosition = -1;
                    playbackProgressAt = android.os.SystemClock.uptimeMillis();
                    buffering = false;
                    samplePlayback(current, request, true);
                    active.run();
                }
                return false;
            });
            current.setOnCompletionListener(p -> {
                if (!stale(request)) {
                    previewClock.finish();
                    progressChanged.run();
                    ended.run();
                    if (!stale(request)) stop();
                }
            });
            current.setOnErrorListener((p, what, extra) -> {
                android.util.Log.w("TrailerBackdrop", "Player error=" + what + "/" + extra);
                if (!stale(request)) { stop(); finished.run(); }
                return true;
            });
            current.prepareAsync();
        } catch (Exception e) { android.util.Log.w("TrailerBackdrop", "Player unavailable: " + e.getClass().getSimpleName()); stop(); finished.run(); }
    }
    /** Real media time controls the fill, fade and limit; buffering cannot consume the remaining preview. */
    private void samplePlayback(MediaPlayer current, int request, boolean repeat) {
        if (stale(request) || current != player || !rendering) return;
        try {
            int position = current.getCurrentPosition();
            long now = android.os.SystemClock.uptimeMillis();
            if (position > playbackPosition) {
                playbackPosition = position;
                playbackProgressAt = now;
            } else if (now - playbackProgressAt >= TrailerRequests.INACTIVITY_MS) {
                android.util.Log.w("TrailerBackdrop", "Preview playback stalled");
                stop();
                finished.run();
                return;
            }
            previewClock.sample(position, playbackLimit, android.os.SystemClock.uptimeMillis(),
                    !buffering && current.isPlaying());
            float volume = Math.max(0f, Math.min(1f, (playbackLimit - position) / 3000f));
            outputGain = volume;
            if (muted) volume = 0f;
            current.setVolume(volume, volume);
            if (audio != null) audio.setVolume(volume, volume);
            progressChanged.run();
            if (position >= playbackLimit) {
                previewClock.finish();
                ended.run();
                if (!stale(request)) stop();
                return;
            }
        } catch (IllegalStateException ignored) {
            // A player being released cannot update the current slide.
        }
        if (repeat && !stale(request)) main.postDelayed(() -> samplePlayback(current, request, true), 100);
    }

    /**
     * The separate sound track, joined at the picture's position once it is ready.
     * ponytail: two players kept in step only by that one seek; a sync loop if drift shows over the 30 s.
     */
    private void startAudio(MediaPlayer picture, int request) {
        if (pendingAudio == null || audio != null) return;
        try {
            MediaPlayer sound = new MediaPlayer();
            audio = sound;
            sound.setDataSource(pendingAudio);
            sound.setOnPreparedListener(p -> {
                if (stale(request)) return;
                try { p.seekTo(picture.getCurrentPosition()); } catch (Exception ignored) {}
                p.start();
                android.util.Log.i("TrailerBackdrop", "Separate audio started");
            });
            sound.setOnErrorListener((p, what, extra) -> {
                android.util.Log.w("TrailerBackdrop", "Audio error=" + what + "/" + extra);
                return true;   // the picture carries on without sound
            });
            sound.prepareAsync();
        } catch (Exception e) {
            android.util.Log.w("TrailerBackdrop", "Audio unavailable: " + e.getClass().getSimpleName());
        }
    }
    private void crop(int w, int h) {
        if (w <= 0 || h <= 0 || view.getWidth() == 0 || view.getHeight() == 0) return;
        float scale = Math.max((float) view.getWidth() / w, (float) view.getHeight() / h);
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setScale(w * scale / view.getWidth(), h * scale / view.getHeight(), view.getWidth() / 2f, view.getHeight() / 2f);
        view.setTransform(matrix);
    }
    /** Leaving Home or hiding the shelf must also invalidate work already running on the worker. */
    void suspend() {
        requests.cancelPrewarm();
        stop();
    }
    void stop() {
        requests.stop(); busy = false; rendering = false; buffering = false; pendingUrl = null;
        previewClock.reset();
        main.removeCallbacksAndMessages(null);
        if (view != null) { view.animate().cancel(); view.setAlpha(0f); view.setVisibility(View.GONE); }
        LiquidGlass.setVideoFrame(null);   // the glass goes back to the poster
        if (player != null) { player.release(); player = null; }
        if (audio != null) { audio.release(); audio = null; }
        pendingAudio = null;
        if (surface != null) { surface.release(); surface = null; }
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) { play(requests.current()); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
        if (player != null) crop(player.getVideoWidth(), player.getVideoHeight());
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { stop(); return true; }
    /**
     * The dock and the other glass show what is behind them: while the trailer plays that is the video, not the poster
     * under it. A small frame on every video frame, so the glass moves with the picture (LiquidGlass.setVideoFrame).
     */
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (!rendering || !LiquidGlass.enabled || view == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastGlassFrame < GLASS_FRAME_MS) return;
        lastGlassFrame = now;
        if (glassFrame == null) glassFrame = android.graphics.Bitmap.createBitmap(128, 72, android.graphics.Bitmap.Config.ARGB_8888);
        view.getBitmap(glassFrame);
        LiquidGlass.setVideoFrame(glassFrame, view.getAlpha());
    }
    /** Native Android 11 HLS rejects CMAF. Repackage a short clear preview, without decoding it. */
    private String compatiblePreview(String videoUrl, String audioUrl, int request) throws Exception {
        java.io.File directory = new java.io.File(poster.getContext().getCacheDir(), "trailer-previews");
        if (!directory.exists() && !directory.mkdirs()) throw new java.io.IOException("Preview cache unavailable");
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((videoUrl + (audioUrl != null ? audioUrl : "")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte b : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        java.io.File output = new java.io.File(directory, name + "-audio.mp4");
        if (output.isFile() && output.length() > 1024) return output.getAbsolutePath();
        String playlist = get(videoUrl, request);
        if (!playlist.contains("#EXT-X-MAP:")) return videoUrl;
        if (playlist.contains("#EXT-X-KEY:")) throw new java.io.IOException("Not a clear preview");
        java.util.regex.Matcher init = java.util.regex.Pattern.compile("#EXT-X-MAP:URI=\"([^\"]+)\"").matcher(playlist);
        if (!init.find()) throw new java.io.IOException("No initialization fragment");
        java.io.File raw = new java.io.File(directory, name + "-" + request + ".part");
        java.io.File rawAudio = audioUrl != null ? new java.io.File(directory, name + "-audio-" + request + ".part") : null;
        java.io.File temporary = new java.io.File(directory, name + "-" + request + ".tmp");
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(raw)) {
                copyFragment(new java.net.URL(new java.net.URL(videoUrl), init.group(1)).toString(), out, request);
                double seconds = 0;
                double duration = 0;
                for (String line : playlist.split("\\r?\\n")) {
                    if (line.startsWith("#EXTINF:")) duration = Double.parseDouble(line.substring(8).split(",")[0]);
                    else if (!line.isEmpty() && !line.startsWith("#")) {
                        copyFragment(new java.net.URL(new java.net.URL(videoUrl), line.trim()).toString(), out, request);
                        seconds += duration;
                        if (seconds >= 30) break;
                    }
                }
            }
            if (audioUrl != null && rawAudio != null) {
                try {
                    String audioPlaylist = get(audioUrl, request);
                    if (!audioPlaylist.contains("#EXT-X-KEY:")) {
                        java.util.regex.Matcher aInit = java.util.regex.Pattern.compile("#EXT-X-MAP:URI=\"([^\"]+)\"").matcher(audioPlaylist);
                        if (aInit.find()) {
                            try (java.io.FileOutputStream aOut = new java.io.FileOutputStream(rawAudio)) {
                                copyFragment(new java.net.URL(new java.net.URL(audioUrl), aInit.group(1)).toString(), aOut, request);
                                double seconds = 0;
                                double duration = 0;
                                for (String line : audioPlaylist.split("\\r?\\n")) {
                                    if (line.startsWith("#EXTINF:")) duration = Double.parseDouble(line.substring(8).split(",")[0]);
                                    else if (!line.isEmpty() && !line.startsWith("#")) {
                                        copyFragment(new java.net.URL(new java.net.URL(audioUrl), line.trim()).toString(), aOut, request);
                                        seconds += duration;
                                        if (seconds >= 30) break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("TrailerBackdrop", "Audio track unavailable: " + e.getClass().getSimpleName());
                    if (rawAudio.exists()) rawAudio.delete();
                }
            }
            if (stale(request)) throw new java.io.IOException("Preview changed");
            android.media.MediaExtractor videoExtractor = new android.media.MediaExtractor();
            android.media.MediaExtractor audioExtractor = null;
            android.media.MediaMuxer muxer = null;
            try {
                videoExtractor.setDataSource(raw.getAbsolutePath());
                int video = -1;
                for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                    String mime = videoExtractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) { video = i; break; }
                }
                if (video < 0) throw new java.io.IOException("No preview video track");
                videoExtractor.selectTrack(video);

                int audio = -1;
                if (rawAudio != null && rawAudio.isFile() && rawAudio.length() > 0) {
                    try {
                        audioExtractor = new android.media.MediaExtractor();
                        audioExtractor.setDataSource(rawAudio.getAbsolutePath());
                        for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                            String mime = audioExtractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                            if (mime != null && mime.startsWith("audio/")) { audio = i; break; }
                        }
                        if (audio >= 0) audioExtractor.selectTrack(audio);
                        else { audioExtractor.release(); audioExtractor = null; }
                    } catch (Exception e) {
                        android.util.Log.w("TrailerBackdrop", "Audio extraction unavailable: " + e.getClass().getSimpleName());
                        if (audioExtractor != null) { audioExtractor.release(); audioExtractor = null; }
                        audio = -1;
                    }
                }

                muxer = new android.media.MediaMuxer(temporary.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int videoTrack = muxer.addTrack(videoExtractor.getTrackFormat(video));
                int audioTrack = (audioExtractor != null && audio >= 0) ? muxer.addTrack(audioExtractor.getTrackFormat(audio)) : -1;
                muxer.start();

                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024);
                android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
                long firstVideo = videoExtractor.getSampleTime();
                long firstAudio = audioExtractor != null ? audioExtractor.getSampleTime() : 0;

                while (!stale(request)) {
                    long vTime = videoExtractor.getSampleTime();
                    long aTime = audioExtractor != null ? audioExtractor.getSampleTime() : -1;
                    if (vTime < 0 && aTime < 0) break;

                    boolean pickVideo = (aTime < 0) || (vTime >= 0 && (vTime - firstVideo <= aTime - firstAudio));
                    if (pickVideo) {
                        int count = videoExtractor.readSampleData(buffer, 0);
                        if (count < 0) break;
                        info.set(0, count, vTime - firstVideo, (videoExtractor.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        muxer.writeSampleData(videoTrack, buffer, info);
                        videoExtractor.advance();
                    } else {
                        int count = audioExtractor.readSampleData(buffer, 0);
                        if (count < 0) {
                            audioExtractor.release();
                            audioExtractor = null;
                        } else {
                            info.set(0, count, aTime - firstAudio, (audioExtractor.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                            muxer.writeSampleData(audioTrack, buffer, info);
                            audioExtractor.advance();
                        }
                    }
                }
                muxer.stop();
            } finally {
                videoExtractor.release();
                if (audioExtractor != null) audioExtractor.release();
                if (muxer != null) muxer.release();
            }
            if (stale(request) || !temporary.renameTo(output)) throw new java.io.IOException("Preview changed");
            android.util.Log.i("TrailerBackdrop", "Compatible 30-second preview ready");
            return output.getAbsolutePath();
        } finally {
            raw.delete();
            if (rawAudio != null) rawAudio.delete();
            temporary.delete();
        }
    }
    /**
     * YouTube's sound track saved to the cache first: played straight from the network the TV's extractor stalls in
     * prepare and never starts. A few MB; null (no sound) when it cannot be fetched.
     */
    private String localAudio(String url, String id, int request) {
        java.io.File directory = new java.io.File(poster.getContext().getCacheDir(), "trailer-previews");
        java.io.File file = new java.io.File(directory, "yt-" + id.replaceAll("[^A-Za-z0-9_-]", "") + "-flat.m4a");
        if (file.isFile() && file.length() > 0) return file.getAbsolutePath();
        java.io.File part = new java.io.File(directory, file.getName() + ".part");
        java.io.File flat = new java.io.File(directory, file.getName() + ".mux");
        try {
            directory.mkdirs();
            // range= in the query is how YouTube serves a file in one go instead of throttling it.
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                copyFragment(url + "&range=0-11999999", out, request);
            }
            // YouTube's file is fragmented, which the TV's own MP4 reader plays as empty: copy it into a plain one.
            android.media.MediaExtractor extractor = new android.media.MediaExtractor();
            android.media.MediaMuxer muxer = null;
            try {
                extractor.setDataSource(part.getAbsolutePath());
                int track = -1;
                for (int i = 0; i < extractor.getTrackCount() && track < 0; i++) {
                    String mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) track = i;
                }
                if (track < 0) throw new java.io.IOException("No audio track");
                extractor.selectTrack(track);
                muxer = new android.media.MediaMuxer(flat.getAbsolutePath(),
                        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int out = muxer.addTrack(extractor.getTrackFormat(track));
                muxer.start();
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(256 * 1024);
                android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
                // The picture stops at 30 s (play), so 35 s of sound is enough.
                while (!stale(request) && extractor.getSampleTime() >= 0 && extractor.getSampleTime() < 35_000_000L) {
                    info.size = extractor.readSampleData(buffer, 0);
                    if (info.size < 0) break;
                    info.offset = 0;
                    info.presentationTimeUs = extractor.getSampleTime();
                    info.flags = android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME;   // every AAC frame stands alone
                    muxer.writeSampleData(out, buffer, info);
                    extractor.advance();
                }
                muxer.stop();
            } finally {
                extractor.release();
                if (muxer != null) muxer.release();
                part.delete();
            }
            if (stale(request)) { flat.delete(); return null; }
            return flat.renameTo(file) ? file.getAbsolutePath() : null;
        } catch (Exception e) {
            android.util.Log.w("TrailerBackdrop", "Audio preparation failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            part.delete();
            flat.delete();
        }
    }
    /**
     * The first 30 s of YouTube's video-only picture, downloaded and muxed with its sound ({@code audioPath}) into
     * one plain MP4: in step, and from the disk it starts at once. Null when that fails (the caller streams instead).
     */
    private String joinYouTube(String videoUrl, String audioPath, String id, int request) {
        java.io.File directory = new java.io.File(poster.getContext().getCacheDir(), "trailer-previews");
        java.io.File file = new java.io.File(directory, "yt-" + safeId(id) + "-av.mp4");
        if (file.isFile() && file.length() > 0) return file.getAbsolutePath();
        java.io.File part = new java.io.File(directory, file.getName() + ".part");
        java.io.File mux = new java.io.File(directory, file.getName() + ".mux");
        android.media.MediaExtractor video = new android.media.MediaExtractor();
        android.media.MediaExtractor sound = new android.media.MediaExtractor();
        android.media.MediaMuxer muxer = null;
        try {
            // ponytail: 16 MB is 25-30 s at 1080p (a cut-short file just ends the trailer early); by bitrate if needed.
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                copyFragment(videoUrl + "&range=0-15999999", out, request);
            }
            video.setDataSource(part.getAbsolutePath());
            sound.setDataSource(audioPath);
            int v = firstTrack(video, "video/"), a = firstTrack(sound, "audio/");
            if (v < 0 || a < 0) throw new java.io.IOException("Missing track");
            video.selectTrack(v);
            sound.selectTrack(a);
            muxer = new android.media.MediaMuxer(mux.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = muxer.addTrack(video.getTrackFormat(v)), aOut = muxer.addTrack(sound.getTrackFormat(a));
            muxer.start();
            copySamples(video, muxer, vOut, true, request);
            copySamples(sound, muxer, aOut, false, request);
            muxer.stop();
            if (stale(request)) { mux.delete(); return null; }
            if (!mux.renameTo(file)) return null;
            return file.getAbsolutePath();
        } catch (Exception e) {
            android.util.Log.w("TrailerBackdrop", "Joining YouTube preview failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            mux.delete();
            return null;
        } finally {
            video.release();
            sound.release();
            if (muxer != null) try { muxer.release(); } catch (RuntimeException ignored) {}
            part.delete();
            mux.delete();
        }
    }
    /**
     * Playback stops with its generation; a prewarm survives showing its target, but not leaving Home.
     * TrailerRequests also invalidates prewarms when a different slide is shown.
     */
    private boolean stale(int request) {
        return requests.stale(request);
    }
    private static String safeId(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "");
    }
    private static int firstTrack(android.media.MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }
    /** Samples of the selected track up to 30 s, or until a download cut short ends them. */
    private void copySamples(android.media.MediaExtractor in, android.media.MediaMuxer muxer, int track, boolean video, int request) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024 * 1024);
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        while (!stale(request) && in.getSampleTime() >= 0 && in.getSampleTime() < 30_000_000L) {
            int size;
            try { size = in.readSampleData(buffer, 0); } catch (RuntimeException cutShort) { break; }
            if (size < 0) break;
            boolean key = !video || (in.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
            info.set(0, size, in.getSampleTime(), key ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(track, buffer, info);
            if (!in.advance()) break;
        }
    }
    private void copyFragment(String url, java.io.FileOutputStream out, int request) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(5000);
        try (java.io.InputStream in = c.getInputStream()) {
            byte[] bytes = new byte[32768]; int n;
            while ((n = in.read(bytes)) != -1) {
                if (stale(request)) throw new java.io.IOException("Preview changed");
                if (out.getChannel().position() + n > 24 * 1024 * 1024) throw new java.io.IOException("Preview too large");
                out.write(bytes, 0, n);
                loadingProgress(request);
            }
        } finally { c.disconnect(); }
    }
    private String get(String url, int request) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(5000);
        try (java.io.InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; int n;
            while ((n = in.read(bytes)) != -1) {
                if (stale(request)) throw new java.io.IOException("Preview changed");
                if (out.size() + n > 2 * 1024 * 1024) throw new java.io.IOException("Preview page too large");
                out.write(bytes, 0, n);
                loadingProgress(request);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
    }
}
