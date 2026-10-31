package com.gathin.tvhome;

/** Interrupted capsule morphs start at the currently drawn weights, never at a previous target. */
final class IndicatorState {
    static final int MORPH_MS = 320;
    static final float DOT = 5f, PILL = 14f, GAP = 7f;
    private float[] weights = new float[0], from = new float[0];
    private int selected = -1;
    private long started;
    private boolean moving;

    void reset(int count) {
        weights = new float[Math.max(0, count)];
        from = new float[weights.length];
        selected = -1;
        moving = false;
    }
    int count() { return weights.length; }
    int selected() { return selected; }
    float weight(int i) { return weights[i]; }
    float width(int i) { return DOT + (PILL - DOT) * weights[i]; }
    float width() { return count() == 0 ? 0 : count() * DOT + PILL - DOT + (count() - 1) * GAP; }
    void select(int index, long now, boolean animate) {
        if (index < 0 || index >= count()) return;
        update(now);
        if (selected == index) return;
        System.arraycopy(weights, 0, from, 0, count());
        moving = animate && selected >= 0 && selected != index;
        selected = index;
        started = now;
        if (!moving) for (int i = 0; i < count(); i++) weights[i] = i == selected ? 1f : 0f;
    }
    boolean update(long now) {
        if (!moving) return false;
        float t = Math.max(0f, Math.min(1f, (float) (now - started) / MORPH_MS));
        float eased = 1f - (1f - t) * (1f - t) * (1f - t);
        for (int i = 0; i < count(); i++) weights[i] = from[i] + ((i == selected ? 1f : 0f) - from[i]) * eased;
        moving = t < 1f;
        return moving;
    }
}
