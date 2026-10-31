package com.gathin.tvhome;

/** Playback-position samples with at most 100 ms interpolation; buffering never advances the preview clock. */
final class PreviewClock {
    private long position, sampledAt, duration, displayed;
    private boolean running;
    void reset() { position = sampledAt = duration = displayed = 0; running = false; }
    void sample(long positionMs, long durationMs, long now, boolean playing) {
        position = Math.max(0, positionMs);
        duration = Math.max(1, durationMs);
        sampledAt = now;
        running = playing;
    }
    void finish() { position = displayed = duration; running = false; }
    boolean running() { return running; }
    float fraction(long now) {
        if (duration == 0) return 0f;
        long estimate = position + (running ? Math.max(0, Math.min(100, now - sampledAt)) : 0);
        displayed = Math.min(duration, Math.max(displayed, estimate));
        return (float) displayed / duration;
    }
}
