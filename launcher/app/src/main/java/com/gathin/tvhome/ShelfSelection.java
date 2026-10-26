package com.gathin.tvhome;

/** Keeps the displayed slide stable while newer artwork loads; all calls run on the UI thread. */
final class ShelfSelection<T> {
    private int generation;
    private T visible;
    int begin() { return ++generation; }
    boolean isCurrent(int request) { return request == generation; }
    boolean commit(int request, T slide) {
        if (!isCurrent(request)) return false;
        visible = slide;
        return true;
    }
    T visible() { return visible; }
    void clear() { ++generation; visible = null; }
}
