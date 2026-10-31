package com.gathin.tvhome;

/** Request lifetimes: replacing playback can retain a matching prewarm; leaving Home cancels both. */
final class TrailerRequests {
    static final long INACTIVITY_MS = 30_000;
    private volatile int generation;
    private volatile int serial;
    private volatile long progressAt;
    private String target;

    int current() { return generation; }
    boolean prewarming(String next) { return next.equals(target); }
    void progress(int request, long now) {
        if (!stale(request)) progressAt = now;
    }
    boolean stalled(long now) { return now - progressAt >= INACTIVITY_MS; }
    int prewarm(String next) {
        target = next;
        return -serial - 1;
    }
    void show(String next) {
        if (!next.equals(target)) ++serial;
        target = null;
    }
    void stop() { ++generation; }
    void cancelPrewarm() { ++serial; target = null; }
    boolean stale(int request) {
        return request >= 0 ? request != generation : -request - 1 != serial;
    }
}
