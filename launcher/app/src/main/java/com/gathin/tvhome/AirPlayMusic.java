package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.TextClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Optional same-signature AirPlay bridge; observed only while Home is visible. */
final class AirPlayMusic {
    private final Uri URI = Uri.parse("content://io.github.besliky.airplaytv.launcher/session");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Context context;
    private final Pill pill;
    private final Consumer<Boolean> mute;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;
    private int generation;
    private MediaController controller;
    private PendingIntent open;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean self) { refresh(); }
    };
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { render(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { render(); }
        @Override public void onSessionDestroyed() { detach(); render(); refresh(); }
    };

    AirPlayMusic(Context context, Pill pill, Consumer<Boolean> mute) {
        this.context = context; this.pill = pill; this.mute = mute;
    }

    void start() {
        if (started) return;
        started = true;
        try { context.getContentResolver().registerContentObserver(URI, false, observer); }
        catch (RuntimeException ignored) { /* optional app absent or differently signed */ }
        refresh();
    }

    void stop() {
        if (!started) return;
        started = false;
        generation++;
        context.getContentResolver().unregisterContentObserver(observer);
        detach();
        // Retain the last mute state until the next snapshot, avoiding an audible Home-return flash.
    }

    private void refresh() {
        if (!started) return;
        final int request = ++generation;
        IO.execute(() -> {
            Bundle result = null;
            try { result = context.getContentResolver().call(URI, "session", null, null); }
            catch (RuntimeException ignored) { /* retain ordinary clock when bridge unavailable */ }
            final Bundle snapshot = result;
            main.post(() -> {
                if (!started || request != generation) return;
                detach();
                if (snapshot != null) {
                    snapshot.setClassLoader(MediaSession.Token.class.getClassLoader());
                    MediaSession.Token token = snapshot.getParcelable("token");
                    open = snapshot.getParcelable("open");
                    if (token != null) {
                        controller = new MediaController(context, token);
                        controller.registerCallback(callback, main);
                    }
                }
                render();
            });
        });
    }

    private void detach() {
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
        open = null;
    }

    private void render() {
        MediaMetadata metadata = controller == null ? null : controller.getMetadata();
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        String title = metadata == null ? "" : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        boolean active = visible(title, state == null ? PlaybackState.STATE_NONE : state.getState());
        pill.setMusic(active, metadata, state != null && state.getState() == PlaybackState.STATE_PLAYING);
        mute.accept(active);
    }

    static boolean visible(String title, int state) {
        return title != null && !title.trim().isEmpty()
                && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
                || state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING);
    }

    boolean openPlayer() {
        if (!pill.active || pill.avatarSelected || open == null) return false;
        try { open.send(); return true; }
        catch (PendingIntent.CanceledException ignored) { refresh(); return false; }
    }

    /** A single clock/music surface, with a separate remote-selected avatar end. */
    static final class Pill extends TextClock {
        private final float d, sp;
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();
        private Bitmap cover;
        private String title = "", artist = "";
        private boolean active, playing, avatarSelected;
        private final boolean animations;
        private float mix;
        private android.animation.ValueAnimator transition;
        private final android.graphics.Path clip = new android.graphics.Path();

        Pill(Context context, boolean animations) {
            super(context, null, 0, R.style.TopButton);
            this.animations = animations;
            d = getResources().getDisplayMetrics().density;
            sp = getResources().getDisplayMetrics().scaledDensity;
        }

        void setMusic(boolean active, MediaMetadata metadata, boolean playing) {
            boolean changed = this.active != active;
            this.active = active;
            this.playing = playing;
            if (metadata != null) {
                title = safe(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
                artist = safe(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
                cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            }
            if (!active) avatarSelected = false;
            setContentDescription(active ? title + ", " + artist + ". "
                    + getContext().getString(R.string.airplay_card_hint) : getContext().getString(R.string.settings));
            if (changed) {
                if (transition != null) transition.cancel();
                if (animations && isShown()) {
                    transition = android.animation.ValueAnimator.ofFloat(mix, active ? 1f : 0f);
                    transition.setDuration(220);
                    transition.addUpdateListener(value -> {
                        mix = (float) value.getAnimatedValue();
                        requestLayout(); invalidate();
                    });
                    transition.start();
                } else { mix = active ? 1f : 0f; requestLayout(); }
            }
            invalidate();
        }

        private static String safe(String value) { return value == null ? "" : value; }

        boolean handleKey(int key, KeyEvent event) {
            if (!active) return false;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && !avatarSelected) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) { avatarSelected = true; invalidate(); }
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && avatarSelected) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) { avatarSelected = false; invalidate(); }
                return true;
            }
            return false;
        }

        @Override protected void onMeasure(int width, int height) {
            super.onMeasure(width, height);
            if (mix > 0f) setMeasuredDimension(resolveSize(Math.round(
                    getMeasuredWidth() + (272 * d - getMeasuredWidth()) * mix), width), getMeasuredHeight());
        }

        @Override protected void onDraw(Canvas canvas) {
            if (mix < 1f) {
                int clockLayer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * (1 - mix)));
                super.onDraw(canvas); canvas.restoreToCount(clockLayer);
            }
            if (mix <= 0f) return;
            int musicLayer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * mix));
            int color = getCurrentTextColor();
            float cy = getHeight() / 2f;
            rect.set(6 * d, cy - 16 * d, 38 * d, cy + 16 * d);
            paint.setColor(0x335F7180);
            canvas.drawRoundRect(rect, 7 * d, 7 * d, paint);
            if (cover != null && !cover.isRecycled()) {
                int save = canvas.save();
                clip.reset();
                clip.addRoundRect(rect, 7 * d, 7 * d, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                paint.setAlpha(255);
                canvas.drawBitmap(cover, null, rect, paint);
                canvas.restoreToCount(save);
            }
            text.setColor(color);
            text.setTextSize(14 * sp);
            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            float room = Math.max(0, getWidth() - 94 * d);
            canvas.drawText(TextUtils.ellipsize(title, text, room, TextUtils.TruncateAt.END).toString(), 48 * d, cy - 1 * d, text);
            text.setTextSize(11 * sp);
            text.setAlpha(170);
            text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            canvas.drawText(TextUtils.ellipsize(artist, text, room, TextUtils.TruncateAt.END).toString(), 48 * d, cy + 13 * d, text);
            Drawable avatar = getCompoundDrawablesRelative()[2];
            float x = getWidth() - 37 * d;
            if (avatar != null) {
                canvas.save(); canvas.translate(x, cy - 16 * d); avatar.draw(canvas); canvas.restore();
            }
            paint.setColor(playing ? 0xFF5DC9FF : color);
            canvas.drawCircle(36 * d, cy + 14 * d, 2.5f * d, paint);
            if (isFocused() && avatarSelected) {
                paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f * d);
                canvas.drawCircle(x + 16 * d, cy, 18 * d, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            canvas.restoreToCount(musicLayer);
        }

        @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect old) {
            super.onFocusChanged(focused, direction, old);
            if (!focused) avatarSelected = false;
        }

        @Override protected void onDetachedFromWindow() {
            if (transition != null) transition.cancel();
            super.onDetachedFromWindow();
        }
    }
}
