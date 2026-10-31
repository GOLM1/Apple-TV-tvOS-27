package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** A single canvas keeps the capsule, neighbouring dots and their centre aligned throughout each morph. */
// Constructed in code with a required playback provider; never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
final class PageIndicator extends View {
    interface Progress {
        float fraction();
        boolean running();
    }
    private final IndicatorState state = new IndicatorState();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float density;
    private final Progress progress;
    private float[] outgoing = new float[0];
    private boolean paused, holding;
    private float held;

    PageIndicator(Context context, Progress progress) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        this.progress = progress;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void setCount(int count) {
        state.reset(count);
        outgoing = new float[state.count()];
        holding = false;
        requestLayout();
        invalidate();
    }
    boolean holding() { return holding; }
    void beginSelection(int index, boolean animate) {
        if (state.selected() >= 0) outgoing[state.selected()] = fraction();
        state.select(index, SystemClock.uptimeMillis(), animate);
        held = 0f;
        holding = true;
        invalidate();
    }
    void finishSelection(int index) {
        if (state.selected() != index) return;
        holding = false;
        invalidate();
    }
    void setPaused(boolean value) { paused = value; invalidate(); }
    private float fraction() { return holding ? held : Math.max(0f, Math.min(1f, progress.fraction())); }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize(Math.round(state.width() * density) + getPaddingLeft() + getPaddingRight(), w),
                resolveSize(Math.round(IndicatorState.DOT * density) + getPaddingTop() + getPaddingBottom(), h));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        boolean moving = state.update(now);
        float x = (getWidth() - state.width() * density) / 2f;
        float cy = getHeight() / 2f;
        float value = fraction();
        float focus = 0f;
        for (int i = 0; i < state.count(); i++) focus += i * state.weight(i);
        for (int i = 0; i < state.count(); i++) {
            float width = state.width(i) * density;
            float weight = state.weight(i);
            float distance = Math.abs(i - focus);
            float edge = state.count() < 8 ? 1f : Math.max(0.45f, Math.min(1f, 1f - (distance - (state.count() - 3f)) * 0.3f));
            float height = IndicatorState.DOT * density * (edge + (1f - edge) * weight);
            float drawnWidth = width - IndicatorState.DOT * density * (1f - edge) * (1f - weight);
            float left = x + (width - drawnWidth) / 2f;
            bounds.set(left, cy - height / 2f, left + drawnWidth, cy + height / 2f);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(90 * edge));
            canvas.drawRoundRect(bounds, height / 2f, height / 2f, paint);
            if (weight > 0f) {
                // The first white circle remains visible at 0%, just like the recorded reference.
                float fill = i == state.selected() ? value : outgoing[i];
                bounds.right = bounds.left + Math.min(drawnWidth, height + (drawnWidth - height) * fill);
                paint.setAlpha(Math.round(255 * weight));
                canvas.drawRoundRect(bounds, height / 2f, height / 2f, paint);
            }
            x += width + IndicatorState.GAP * density;
        }
        if (!paused && isShown() && (moving || !holding && progress.running())) postInvalidateOnAnimation();
    }
}
