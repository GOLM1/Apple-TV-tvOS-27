package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

/**
 * glass_style tvos: the light catching a focused tile, drawn on the tile itself (its foreground), as on Apple TV
 * where the icon lifts and shines rather than a pane being laid over it. A soft diagonal band sweeps across from the
 * side focus came from, brighter while it moves, then settles faint near the top left. Clipped to the tile's corners.
 */
final class TileSheen extends Drawable {
    /** Where the band rests, as a fraction of the width, and how bright it is moving and at rest (alpha 0-255). */
    static final float REST = 0.3f;
    private static final int MOVING_ALPHA = 0x3D, REST_ALPHA = 0x16;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private float position = REST;
    private float brightness = REST_ALPHA;
    private ValueAnimator sweep;
    private LinearGradient gradient;
    private int shaderWidth = -1, shaderHeight = -1;
    private final android.graphics.Matrix shift = new android.graphics.Matrix();

    TileSheen(float radiusPx) {
        radius = radiusPx;
    }

    /** Sweep the band from {@code from} (below 0 starts off the left edge, near 1 at the right) to its rest. */
    void sweep(float from, boolean animate) {
        if (sweep != null) sweep.cancel();
        if (!animate) {
            position = REST;
            brightness = REST_ALPHA;
            invalidateSelf();
            return;
        }
        sweep = ValueAnimator.ofFloat(0f, 1f);
        sweep.setDuration(560);
        sweep.setInterpolator(new DecelerateInterpolator(1.6f));
        sweep.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            position = from + (REST - from) * t;
            brightness = MOVING_ALPHA + (REST_ALPHA - MOVING_ALPHA) * t;
            invalidateSelf();
        });
        sweep.start();
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float w = b.width(), h = b.height();
        // One gradient per size, white at full strength; the sweep only slides it and changes the paint's alpha, so
        // a frame of the animation allocates nothing.
        if (b.width() != shaderWidth || b.height() != shaderHeight) {
            gradient = new LinearGradient(-w * 0.55f, b.top - h * 0.2f, w * 0.25f, b.top + h * 0.9f,
                    new int[]{0x00FFFFFF, 0xFFFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            paint.setShader(gradient);
            shaderWidth = b.width();
            shaderHeight = b.height();
        }
        shift.setTranslate(b.left + w * position, 0);
        gradient.setLocalMatrix(shift);
        paint.setAlpha(Math.round(brightness));
        rect.set(b);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter colorFilter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
