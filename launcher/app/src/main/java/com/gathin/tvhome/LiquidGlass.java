package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A stand-in for Apple's Liquid Glass on Android 11, where there is no RenderEffect (12) or RuntimeShader (13) to
 * blur or refract what is behind a view. The Top Shelf's backdrop is kept as a small, smoothly blurred copy; each
 * glass surface draws the part of it that lies behind it on screen, stretched with bilinear filtering, following the
 * picture's slow drift and its fades. Over it: a touch more colour (vibrancy), the scrim's shade, a faint inner glow
 * and a thin rim of light brightest at two corners (Apple's edge is a line, not a lens).
 */
final class LiquidGlass {

    /** config.xml liquid_glass: the dock band, the profile pill, the play button and the top menu use it. */
    static boolean enabled;

    /** The shelf uses only a local text shadow, so the glass has no screen-wide dark fade to reproduce. */
    static final int[] SCRIM = {0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000};
    /** How much of the scrim the glass takes on: all of it, so it is no lighter than what is around it. */
    private static final float SCRIM_SHARE = 1f;

    /** The backdrop copy's width: about 12 times smaller than the screen, the size of the blur. */
    private static final int BACKDROP_WIDTH = 160;

    private static Bitmap backdrop;
    private static BitmapShader backdropShader;
    /** Clear controls retain more detail than the frosted panels. Built once per artwork change. */
    private static Bitmap clearBackdrop;
    private static BitmapShader clearShader;
    /**
     * The view showing the backdrop (ContinueWatching's continueArt): the glass follows its slow zoom and drift and
     * its opacity, so it moves with the picture, fades with it between slides and dims with it under a scrolled grid.
     */
    private static View backdropView;
    /** The playing trailer's frame, frosted and clear (setVideoFrame); null while no trailer shows. */
    private static Bitmap videoBackdrop, videoClear;
    private static BitmapShader videoShader, videoClearShader;
    private static int[] videoPixels;
    /** Builds the backdrop copies off the main thread (setBackdrop); the newest request wins. */
    private static final java.util.concurrent.ExecutorService BUILDER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Made on first use (setBackdrop runs on the main thread), so the class loads where there is no Looper (unit tests). */
    private static android.os.Handler main;
    private static int backdropGeneration;
    /** The picture being blurred (prepare), the last one blurred and its copies, and a swap waiting for its copy. */
    private static Bitmap preparing, preparedFor, waitingFor;
    private static Bitmap[] prepared;
    /** What prepare's caller runs once its picture's copy exists, and for which picture. */
    private static Runnable onPrepared;
    private static Bitmap onPreparedFor;
    private static int waitingGeneration;
    private static final Matrix matrix = new Matrix();
    private static final int[] location = new int[2];
    /** Every view drawing glass, redrawn when the backdrop changes. */
    private static final Set<View> hosts = Collections.newSetFromMap(new WeakHashMap<>());

    private LiquidGlass() {}

    /**
     * Start blurring {@code art} before it is put up: the shelf calls this as its short fade-out begins, so by the
     * swap the glass copy is ready and the glass changes in the same frame as the picture. The blur takes tens of
     * milliseconds on the TV, too long for the main thread while focus is moving, so it runs on a worker.
     */
    static void prepare(Bitmap art) {
        prepare(art, null);
    }

    /**
     * As {@link #prepare(Bitmap)}, then runs {@code ready} on the main thread once the copy exists (at once if it
     * already does), so the shelf can hold its swap until picture and glass can change together. Only the newest
     * request's {@code ready} runs.
     */
    static void prepare(Bitmap art, Runnable ready) {
        if (art == null) return;
        if (art == preparedFor) {
            onPrepared = null;
            if (ready != null) ready.run();
            return;
        }
        onPrepared = ready;
        onPreparedFor = art;
        if (art == preparing) return;
        preparing = art;
        if (main == null) main = new android.os.Handler(android.os.Looper.getMainLooper());
        final android.os.Handler ui = main;
        BUILDER.execute(() -> {
            Bitmap[] built = build(art);
            ui.post(() -> {
                if (preparing == art) preparing = null;
                preparedFor = art;
                prepared = built;
                // The swap already happened and is waiting for this copy (a slow blur): put it up now.
                if (waitingFor == art) {
                    waitingFor = null;
                    apply(built, waitingGeneration);
                }
                if (onPreparedFor == art && onPrepared != null) {
                    Runnable callback = onPrepared;
                    onPrepared = null;
                    callback.run();
                }
            });
        });
    }

    /** The Top Shelf's new backdrop (any size); null for none. The glass surfaces redraw with it. */
    static void setBackdrop(Bitmap art) {
        final int generation = ++backdropGeneration;
        waitingFor = null;
        if (art == null) {
            apply(null, generation);
            return;
        }
        if (art == preparedFor) {
            apply(prepared, generation);   // ready: in step with the picture
            return;
        }
        // Not prepared (or still blurring): it goes up the moment its copy is ready, unless a newer one came first.
        waitingFor = art;
        waitingGeneration = generation;
        prepare(art);
    }

    private static void apply(Bitmap[] built, int generation) {
        if (generation != backdropGeneration) return;
        backdrop = built == null ? null : built[0];
        backdropShader = backdrop == null ? null : new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        clearBackdrop = built == null ? null : built[1];
        clearShader = clearBackdrop == null ? null : new BitmapShader(clearBackdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        for (View host : hosts) host.invalidate();
    }

    /**
     * A frame of the trailer playing over the backdrop (TrailerBackdrop samples a small one on every video frame);
     * while set, the glass shows it instead of the poster under the video, which it would otherwise keep showing.
     * Null when the trailer stops. The frame already fills the screen as shown, so no drift is applied to it.
     */
    static void setVideoFrame(Bitmap frame) {
        if (frame == null) {
            videoBackdrop = videoClear = null;
            videoShader = videoClearShader = null;
            for (View host : hosts) host.invalidate();
            return;
        }
        // Called on every video frame, so it must be light: the frame is already small (stretched over the glass it
        // blurs itself), one soft box pass, and the same bitmap and buffer every time; updating the bitmap's pixels
        // is enough for the shader to draw the new frame. Frosted and clear share it.
        int w = frame.getWidth(), h = frame.getHeight();
        if (videoBackdrop == null || videoBackdrop.getWidth() != w || videoBackdrop.getHeight() != h) {
            videoBackdrop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            videoShader = new BitmapShader(videoBackdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            videoClear = videoBackdrop;
            videoClearShader = new BitmapShader(videoBackdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            videoPixels = new int[w * h];
        }
        frame.getPixels(videoPixels, 0, w, 0, 0, w, h);
        int[] soft = HeroArt.boxBlur(HeroArt.boxBlur(videoPixels, w, h, 1, true), w, h, 1, false);
        videoBackdrop.setPixels(soft, 0, w, 0, 0, w, h);
        for (View host : hosts) host.invalidate();
    }

    /**
     * The two copies glass draws from: {frosted, clear}. Frosted is halved step by step to about 160 px wide (a bigger
     * filtered step skips pixels and leaves blocks), then three box blur passes, close to a Gaussian: stretched back
     * over a pill or the dock, smooth, yet the shapes behind still show. Clear keeps more detail for clear controls.
     */
    private static Bitmap[] build(Bitmap art) {
        Bitmap small = art;
        int w = art.getWidth();
        while (w / 2 >= BACKDROP_WIDTH) {
            w /= 2;
            small = Bitmap.createScaledBitmap(small, w, Math.max(1, w * art.getHeight() / art.getWidth()), true);
        }
        int h = small.getHeight();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            px = HeroArt.boxBlur(HeroArt.boxBlur(px, w, h, 2, true), w, h, 2, false);
        }
        Bitmap frosted = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        int cw = Math.min(480, art.getWidth());
        int ch = Math.max(1, cw * art.getHeight() / art.getWidth());
        Bitmap detail = Bitmap.createScaledBitmap(art, cw, ch, true);
        int[] clearPixels = new int[cw * ch];
        detail.getPixels(clearPixels, 0, cw, 0, 0, cw, ch);
        clearPixels = HeroArt.boxBlur(HeroArt.boxBlur(clearPixels, cw, ch, 1, true), cw, ch, 1, false);
        return new Bitmap[]{frosted, Bitmap.createBitmap(clearPixels, cw, ch, Bitmap.Config.ARGB_8888)};
    }

    /** The scrim's alpha (0 to 255) at {@code y}, a fraction of the screen's height, between its even stops. */
    static int scrimAlpha(float y) {
        float at = Math.max(0f, Math.min(1f, y)) * (SCRIM.length - 1);
        int i = Math.min((int) at, SCRIM.length - 2);
        float a = SCRIM[i] >>> 24, b = SCRIM[i + 1] >>> 24;
        return Math.round(a + (b - a) * (at - i));
    }

    /** The view the backdrop shows in; its scale, translation and alpha are read on every draw. */
    static void setBackdropView(View view) {
        backdropView = view;
    }

    /** The backdrop moved or faded: redraw the glass. Called a few times a second while it animates. */
    static void frame() {
        for (View host : hosts) host.invalidate();
    }

    /** Redraw {@code host} whenever the backdrop changes. */
    static void register(View host) {
        hosts.add(host);
    }

    /** One glass surface's paints, so each frame allocates nothing; the gradients follow its size. */
    static final class Surface {
        private boolean sampleBackdrop = true;
        private final boolean clear;
        private final Paint bevel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float shaderLeft = Float.NaN, shaderTop = Float.NaN, shaderRight = Float.NaN, shaderBottom = Float.NaN;

        /** @param tintArgb the glass's own colour over the blur: faint for pills and the dock, dark for panels. */
        Surface(float density, int tintArgb) { this(density, tintArgb, false); }

        Surface(float density, int tintArgb, boolean clear) {
            this.clear = clear;
            bevel.setStyle(Paint.Style.STROKE);
            bevel.setStrokeWidth(3 * density);
            tint.setColor(tintArgb);
            // Vibrancy instead of a milky wash: what shows through is a touch more saturated, never brighter, so the
            // glass is as dark as the shaded backdrop around it.
            ColorMatrix vibrancy = new ColorMatrix();
            vibrancy.setSaturation(clear ? 1.06f : 1.15f);
            blur.setColorFilter(new ColorMatrixColorFilter(vibrancy));
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(2 * density);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(density);
        }

        /** Draws the glass in {@code rect} (in {@code host}'s coordinates) on {@code c}. */
        void draw(Canvas c, RectF rect, float radius, View host) {
            if (rect.left != shaderLeft || rect.top != shaderTop || rect.right != shaderRight
                    || rect.bottom != shaderBottom) {
                // A thin line of light, brightest at the top-left and bottom-right corners, as on Apple's glass.
                rim.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                        new int[]{0xE6FFFFFF, 0x26FFFFFF, 0x1AFFFFFF, 0x8CFFFFFF}, new float[]{0f, 0.35f, 0.7f, 1f},
                        Shader.TileMode.CLAMP));
                // A faint light just inside the edge, strongest at the top: a hint of the glass's thickness.
                glow.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x14FFFFFF, 0x03FFFFFF,
                        Shader.TileMode.CLAMP));
                if (clear) {
                    bevel.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                            new int[]{0x48FFFFFF, 0x06FFFFFF, 0x20000000, 0x38FFFFFF},
                            new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                    sheen.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                            new int[]{0x1CFFFFFF, 0x02FFFFFF, 0x00FFFFFF},
                            new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
                }
                shaderLeft = rect.left;
                shaderTop = rect.top;
                shaderRight = rect.right;
                shaderBottom = rect.bottom;
            }
            // A playing trailer takes the poster's place: it covers the poster, so the glass shows the video.
            boolean video = videoShader != null;
            Bitmap texture = video ? (clear ? videoClear : videoBackdrop) : (clear ? clearBackdrop : backdrop);
            BitmapShader shader = video ? (clear ? videoClearShader : videoShader) : (clear ? clearShader : backdropShader);
            if (sampleBackdrop && shader != null && texture != null) {
                // The backdrop fills the screen centre-cropped (continueArt); map its pixels to this view's.
                DisplayMetrics dm = host.getResources().getDisplayMetrics();
                float scale = Math.max((float) dm.widthPixels / texture.getWidth(),
                        (float) dm.heightPixels / texture.getHeight());
                host.getLocationOnScreen(location);
                matrix.setScale(scale, scale);
                matrix.postTranslate((dm.widthPixels - texture.getWidth() * scale) / 2f,
                        (dm.heightPixels - texture.getHeight() * scale) / 2f);
                float alpha = 1f;
                View art = backdropView;
                if (art != null && !video) {
                    // The picture's own slow zoom and drift (ContinueWatching.startDrift), then its fade.
                    matrix.postScale(art.getScaleX(), art.getScaleY(),
                            art.getLeft() + art.getPivotX(), art.getTop() + art.getPivotY());
                    matrix.postTranslate(art.getTranslationX(), art.getTranslationY());
                    alpha = art.getAlpha();
                }
                if (clear) {
                    // A restrained lens magnification gives clear glass a small optical displacement.
                    matrix.postScale(1.025f, 1.025f, location[0] + rect.centerX(), location[1] + rect.centerY());
                }
                matrix.postTranslate(-location[0], -location[1]);
                shader.setLocalMatrix(matrix);
                blur.setShader(shader);
                blur.setAlpha(Math.round((clear ? 175 : 255) * alpha));
                c.drawRoundRect(rect, radius, radius, blur);
                float y = (location[1] + rect.centerY()) / dm.heightPixels;
                dim.setColor(Math.round(scrimAlpha(y) * (clear ? 0.35f : SCRIM_SHARE)) << 24);
                c.drawRoundRect(rect, radius, radius, dim);
            }
            c.drawRoundRect(rect, radius, radius, tint);
            if (clear) {
                c.drawRoundRect(rect, radius, radius, sheen);
                inset(c, rect, radius, bevel.getStrokeWidth(), bevel);
            }
            inset(c, rect, radius, glow.getStrokeWidth(), glow);
            inset(c, rect, radius, rim.getStrokeWidth(), rim);
        }

        /** A stroke of {@code width} drawn just inside {@code rect}'s edge, following its corners. */
        private static void inset(Canvas c, RectF rect, float radius, float width, Paint paint) {
            float in = width / 2f;
            rect.inset(in, in);
            c.drawRoundRect(rect, Math.max(0f, radius - in), Math.max(0f, radius - in), paint);
            rect.inset(-in, -in);
        }
    }

    /** A view background of glass, for the pills, the play button and the menu panel. */
    static final class GlassDrawable extends Drawable {
        private final View host;
        private final Surface surface;
        private final float radius;
        private final RectF rect = new RectF();

        GlassDrawable(View host, float radiusPx, int tintArgb) {
            this(host, radiusPx, tintArgb, true);
        }

        GlassDrawable(View host, float radiusPx, int tintArgb, boolean sampleBackdrop) {
            this(host, radiusPx, tintArgb, sampleBackdrop, false);
        }

        GlassDrawable(View host, float radiusPx, int tintArgb, boolean sampleBackdrop, boolean clear) {
            this.host = host;
            this.radius = radiusPx;
            surface = new Surface(host.getResources().getDisplayMetrics().density, tintArgb, clear);
            surface.sampleBackdrop = sampleBackdrop;
            register(host);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            rect.set(b);
            surface.draw(canvas, rect, Math.min(radius, rect.height() / 2f), host);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
