package com.gathin.tvhome;

/** Limit held-key repeats without queuing moves after release or dropping distinct presses. */
final class PagingRepeat {
    static final long INTERVAL_MS = 180;
    private boolean accepted;
    private int direction;
    private long last;

    boolean accept(int direction, long time, boolean repeat) {
        if (repeat && accepted && this.direction == direction && time - last < INTERVAL_MS) return false;
        accepted = true;
        this.direction = direction;
        last = time;
        return true;
    }
}
