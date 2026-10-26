package com.gathin.tvhome;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * An Apple TV style Top Shelf above the dock, from the system's Watch Next list (what apps publish for the stock
 * Google TV home): the focused dock app's programmes as full-screen slides, each one's artwork filling the screen
 * behind a large title, a Resume button, its progress and page dots. The slides advance by themselves while the dock
 * has focus; Up moves to Resume, where Left and Right page through them and OK opens one. Other apps' rows are only
 * visible with READ_TV_LISTINGS, granted by pm grant; without it the list comes back empty and only the app's own
 * softened artwork shows. The provider refuses a selection from a caller without full EPG access, so filtering and
 * the limit happen here. Artwork is often an https URL, hence INTERNET; nothing else in the launcher uses it.
 */
final class ContinueWatching {

    /** What the shelf asks of Home: hide the dock's glass while Resume has focus, and hand focus on. */
    interface Host {
        void onRowFocused();
        void leaveUp();
        void leaveDown();
    }

    static final class Item {
        final String title, subtitle, packageName, artUri, intentUri;
        final String season, episode;
        final String releaseDate;
        final int programType;
        final long positionMs, durationMs;
        /** From a home screen channel rather than the Watch Next list. */
        final boolean channel;

        Item(String title, String subtitle, String packageName, String artUri, String intentUri,
             long positionMs, long durationMs, boolean channel) {
            this(title, subtitle, packageName, artUri, intentUri, positionMs, durationMs, channel, null, null);
        }

        Item(String title, String subtitle, String packageName, String artUri, String intentUri,
             long positionMs, long durationMs, boolean channel, String season, String episode) {
            this(title, subtitle, packageName, artUri, intentUri, positionMs, durationMs, channel,
                    season, episode, null, -1);
        }

        Item(String title, String subtitle, String packageName, String artUri, String intentUri,
             long positionMs, long durationMs, boolean channel, String season, String episode,
             String releaseDate, int programType) {
            this.releaseDate = releaseDate;
            this.programType = programType;
            this.season = season;
            this.episode = episode;
            this.title = title;
            this.subtitle = subtitle;
            this.packageName = packageName;
            this.artUri = artUri;
            this.intentUri = intentUri;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.channel = channel;
        }
    }

    private static final String TAG = "ContinueWatching";
    /** Read from the provider in one go; each app then shows at most APP_MAX_ITEMS of them. */
    private static final int MAX_ITEMS = 60;
    private static final int APP_MAX_ITEMS = 8;
    private static final int SHELF_ART_PX = 1280;
    private static final long ADVANCE_MS = 8000;
    private static final int PLAY_SIZE_DP = 44;
    private static final float APP_ART_ALPHA = 0.6f, SCROLLED_ART_ALPHA = 0.25f;
    private static final String[] PROJECTION = {
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_RELEASE_DATE,
            TvContract.WatchNextPrograms.COLUMN_TYPE,
    };
    /** Decoded artwork by uri, so moving along the dock does not fetch the same pictures again. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4)) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private final Context ctx;
    private final TrailerBackdrop trailer;
    private final View section, scrim;
    private final ImageView art, titleLogo;
    /** The outgoing picture during a crossfade between apps (holdOldArt); gone otherwise. */
    private ImageView fade;
    private final java.util.Set<String> unavailableLogos = new java.util.HashSet<>();
    private final TextView title, metadata, subtitle, resume, timeLeft, badge, rating;
    /** The source app's round icon before the genres, and the round buttons after Play: info, next slide. */
    private final ImageView appIcon, infoButton, nextButton;
    private final View progressRow;
    private final View progressFill;
    private final LinearLayout dots;
    private final Host host;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Everything the allowed apps published, newest first; the shelf shows the focused dock app's share of it. */
    private List<Item> allItems = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final List<Item> trendingItems = new ArrayList<>();
    /** The focused dock app, and its artwork for the backdrop when it published nothing. */
    private String app;
    private Drawable appArtwork;
    private int selected;
    /** Bumped on every slide change, so a slow picture from an earlier slide never lands on this one. */
    private final ShelfSelection<Slide> selection = new ShelfSelection<>();
    private Slide pendingSlide;
    private boolean restoreFocusWhenReady;
    private int pendingRequest;
    private int refreshGeneration;

    private static final class Slide {
        final Item item;
        Bitmap artwork, logo;
        org.json.JSONObject details;
        boolean artworkReady, titleReady;
        final int index;
        Slide(Item item, Bitmap artwork, int index) {
            this.item = item; this.artwork = artwork; this.index = index;
        }
    }
    /** The grid scrolled its rows up under the top bar: the shelf steps aside so it never sits over them. */
    private boolean hiddenByScroll;
    private boolean paused;
    private float dockTop = -1;
    private String shownKey = "";
    /** The backdrop's opacity at rest: full for a programme's artwork, softer for an app's stretched banner. */
    private float artAlpha = 1f;
    /** Ken Burns motion on the backdrop (startDrift); false with reduced motion. */
    private final boolean motion;
    private AnimatorSet drift;
    private boolean driftLeft;
    /** Until when the glass must follow a fade (uptime ms); it follows the drift for as long as that runs. */
    private long glassFollowUntil;
    /** The picture setArt is fading to, until its dip ends (putUpPendingArt). */
    private Bitmap pendingArt;
    /**
     * Redraws the glass about 8 times a second while the backdrop moves or fades: the drift is slow, so that is
     * smooth enough, and far cheaper than redrawing the dock every frame.
     */
    private final Runnable glassTick = new Runnable() {
        @Override public void run() {
            LiquidGlass.frame();
            boolean drifting = drift != null && drift.isRunning() && !drift.isPaused();
            if (!paused && (drifting || android.os.SystemClock.uptimeMillis() < glassFollowUntil)) {
                main.postDelayed(this, 120);
            }
        }
    };
    private static final float DRIFT_SCALE = 1.06f;
    /** How far a slide travels as it changes, and which way the next change goes (1: from the right). */
    private static final float SLIDE_DP = 64;
    private static final android.view.animation.Interpolator SLIDE_OUT =
            new android.view.animation.AccelerateInterpolator(1.5f);
    private static final android.view.animation.Interpolator SLIDE_IN =
            new android.view.animation.DecelerateInterpolator(2f);
    private int slideDir = 1;
    private final Runnable advance = new Runnable() {
        @Override public void run() {
            if (items.size() > 1 && !paused && !controlsFocused()) select((selected + 1) % items.size());
            scheduleAdvance();
        }
    };

    ContinueWatching(Activity a, boolean motion, Host host) {
        this.motion = motion;
        ctx = a;
        this.host = host;
        // A disk cache under every HttpURLConnection (artwork, logos, TMDB): a slide seen before, today or after a
        // restart, comes off the disk instead of the network.
        if (android.net.http.HttpResponseCache.getInstalled() == null) {
            try {
                android.net.http.HttpResponseCache.install(new java.io.File(a.getCacheDir(), "http"), 64L * 1024 * 1024);
            } catch (IOException e) {
                Log.w(TAG, "no http cache: " + e.getClass().getSimpleName());
            }
        }
        section = a.findViewById(R.id.continueSection);
        art = a.findViewById(R.id.continueArt);
        trailer = new TrailerBackdrop(art, this::scheduleAdvance, this::trailerEnded);
        LiquidGlass.setBackdropView(art);
        scrim = a.findViewById(R.id.continueScrim);
        title = a.findViewById(R.id.continueTitle);
        titleLogo = a.findViewById(R.id.continueLogo);
        subtitle = a.findViewById(R.id.continueSubtitle);
        metadata = a.findViewById(R.id.continueMetadata);
        badge = a.findViewById(R.id.continueBadge);
        rating = a.findViewById(R.id.continueRating);
        appIcon = a.findViewById(R.id.continueAppIcon);
        LinearLayout controls = a.findViewById(R.id.continueRow);
        density = ctx.getResources().getDisplayMetrics().density;
        // The Apple TV app's outlined capsule for "New Season", the boxed rating, and the round app icon.
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(14 * density);
        badgeBg.setColor(0x33000000);
        badgeBg.setStroke(Math.round(1.5f * density), 0xB3FFFFFF);
        badge.setBackground(badgeBg);
        GradientDrawable ratingBg = new GradientDrawable();
        ratingBg.setCornerRadius(3 * density);
        ratingBg.setStroke(Math.max(1, Math.round(density)), 0xCCFFFFFF);
        rating.setBackground(ratingBg);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(0xFF1C1C1E);
        appIcon.setBackground(iconBg);
        appIcon.setClipToOutline(true);
        Typeface medium = UiFont.get(ctx, R.string.font_medium);
        title.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(medium, 700, false)
                : Typeface.create(medium, Typeface.BOLD));
        // Leave the artwork's colours intact. A local soft shadow keeps only the text area legible.
        GradientDrawable textShade = new GradientDrawable();
        textShade.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        textShade.setColors(new int[]{0x66000000, 0x24000000, 0x00000000});
        textShade.setGradientCenter(0.08f, 0.5f);
        textShade.setGradientRadius(390 * density);
        scrim.setBackground(textShade);

        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setClipChildren(false);
        // Room for a focused button to grow and cast its shadow: past the row's edges it was cut off at the bottom.
        int grow = Math.round(10 * density);
        line.setPadding(0, grow, grow, 2 * grow);   // the shadow falls further below than above
        line.setClipToPadding(false);
        controls.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        resume = new TextView(ctx);
        resume.setTextSize(15);
        resume.setTypeface(medium);
        resume.setFocusable(true);
        resume.setGravity(Gravity.CENTER);
        resume.setSingleLine(true);
        int size = Math.round(PLAY_SIZE_DP * density);
        resume.setPadding(Math.round(18 * density), 0, Math.round(18 * density), 0);
        resume.setMinWidth(Math.round(150 * density));
        resume.setMaxWidth(Math.round(320 * density));
        line.addView(resume, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, size));
        styleResume(false);
        // After Play, round buttons as in the Apple TV app: info opens the app itself, next shows the next slide.
        infoButton = roundButton(line, R.drawable.ic_info, R.string.cc_info, size);
        nextButton = roundButton(line, R.drawable.ic_chevron_right, R.string.cc_next, size);
        styleRound(nextButton, false);   // roundButton styled it before the field was set
        infoButton.setOnClickListener(v -> {
            Slide shown = selection.visible();
            Intent launch = shown == null || shown.item.packageName == null ? null
                    : ctx.getPackageManager().getLeanbackLaunchIntentForPackage(shown.item.packageName);
            if (launch != null) ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        nextButton.setOnClickListener(v -> {
            if (items.size() > 1) select((selected + 1) % items.size());
        });
        LinearLayout progressLine = new LinearLayout(ctx);
        progressLine.setOrientation(LinearLayout.HORIZONTAL);
        progressLine.setGravity(Gravity.CENTER_VERTICAL);
        progressRow = progressLine;
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        // Close under the buttons: the row's bottom padding is only room for the focus shadow (line).
        progressLp.topMargin = Math.round(8 * density) - line.getPaddingBottom();
        controls.addView(progressLine, progressLp);
        LinearLayout track = new LinearLayout(ctx);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x4DFFFFFF);
        trackBg.setCornerRadius(2 * density);
        track.setBackground(trackBg);
        track.setWeightSum(1f);
        progressFill = new View(ctx);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(0xFFFFFFFF);
        fillBg.setCornerRadius(2 * density);
        progressFill.setBackground(fillBg);
        track.addView(progressFill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(Math.round(120 * density),
                Math.round(4 * density));
        progressLine.addView(track, trackLp);
        // As wide as Play, so the bar reads as that button's progress.
        resume.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l > 0 && trackLp.width != r - l) {
                trackLp.width = r - l;
                track.setLayoutParams(trackLp);
            }
        });
        timeLeft = new TextView(ctx);
        timeLeft.setTextColor(0xD9FFFFFF);
        timeLeft.setTextSize(13);
        timeLeft.setSingleLine(true);
        timeLeft.setTypeface(medium);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        timeLp.leftMargin = Math.round(12 * density);
        progressLine.addView(timeLeft, timeLp);

        dots = new LinearLayout(ctx);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setPadding(Math.round(10 * density), Math.round(6 * density),
                Math.round(10 * density), Math.round(6 * density));
        dots.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dots.setVisibility(View.GONE);
        GradientDrawable capsule = new GradientDrawable();
        capsule.setColor(0x45000000);
        capsule.setCornerRadius(16 * density);
        dots.setBackground(capsule);
        FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ((ViewGroup) section.getParent()).addView(dots, dotsLp);
        dots.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place());

        resume.setOnFocusChangeListener((v, focused) -> {
            if (focused) host.onRowFocused();
            styleResume(focused);
            v.animate().scaleX(focused && motion ? 1.025f : 1f).scaleY(focused && motion ? 1.025f : 1f)
                    .translationZ(focused ? 8 * density : 0f).setDuration(150).start();
        });
        resume.setOnClickListener(v -> {
            Slide shown = selection.visible();
            if (shown != null) open(shown.item);
        });
        // Left and Right walk Play and the round buttons, and page the slides past the row's ends (controlKey).
        resume.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && controlKey(v, keyCode));
        section.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) place();
        });
    }

    /** A clear primary action, matching the reference's white rounded Resume button. */
    private void styleResume(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PLAY_SIZE_DP / 2f * density);
        bg.setColor(focused ? 0xFFFFFFFF : 0xEBFFFFFF);
        resume.setBackground(bg);
        resume.setTextColor(0xFF18181C);
    }

    /**
     * A round button after Play: a dark translucent circle with a white glyph, white with a dark glyph when focused
     * (the Apple TV app's + and info buttons). Up and Down leave the shelf; Left and Right walk the buttons.
     */
    private ImageView roundButton(LinearLayout line, int icon, int label, int size) {
        ImageView button = new ImageView(ctx);
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setFocusable(true);
        button.setContentDescription(ctx.getString(label));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = Math.round(14 * density);
        line.addView(button, lp);
        styleRound(button, false);
        button.setOnFocusChangeListener((v, focused) -> {
            if (focused) host.onRowFocused();
            styleRound(button, focused);
            v.animate().scaleX(focused && motion ? 1.08f : 1f).scaleY(focused && motion ? 1.08f : 1f)
                    .translationZ(focused ? 8 * density : 0f).setDuration(150).start();
        });
        button.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && controlKey(v, keyCode));
        return button;
    }

    private void styleRound(ImageView button, boolean focused) {
        if (button == nextButton && !focused) {
            // Apple TV's "next" chevron: a bare glyph at rest, the white disc only under focus.
            button.setBackground(null);
            button.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(focused ? 0xFFFFFFFF : 0x80000000);
        if (!focused) bg.setStroke(Math.max(1, Math.round(density)), 0x33FFFFFF);
        button.setBackground(bg);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(focused ? 0xFF18181C : 0xFFFFFFFF));
    }

    /** Play and the round buttons, left to right, skipping any that are hidden. */
    private List<View> controls() {
        List<View> row = new ArrayList<>();
        for (View v : new View[]{resume, infoButton, nextButton}) if (v.getVisibility() == View.VISIBLE) row.add(v);
        return row;
    }

    private boolean controlsFocused() {
        return resume.hasFocus() || infoButton.hasFocus() || nextButton.hasFocus();
    }

    /**
     * D-pad on the shelf's buttons, as in the Apple TV app: Up and Down leave; Left and Right move along the row, and
     * past its ends page the slides (Left on Play to the previous one, Right after the last button to the next one,
     * back on Play), wrapping round at the first and the last slide.
     */
    private boolean controlKey(View from, int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: host.leaveUp(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: host.leaveDown(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                List<View> row = controls();
                int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                int at = row.indexOf(from) + step;
                if (at >= 0 && at < row.size()) {
                    row.get(at).requestFocus();
                } else if (items.size() > 1) {
                    slideDir = step;
                    select(pageTarget(selected, step, items.size()));   // focus stays on the button pressed
                }
                return true;
            }
            default: return false;
        }
    }

    /** The slide one {@code step} (+1 or -1) from {@code selected} among {@code count}, wrapping round. */
    static int pageTarget(int selected, int step, int count) {
        return ((selected + step) % count + count) % count;
    }

    boolean isShown() {
        return !hiddenByScroll && section.getVisibility() == View.VISIBLE && selection.visible() != null;
    }

    /** The dock's top edge in the root's coordinates: the shelf sits right above it. */
    void placeAbove(float dockTopInRoot) {
        if (dockTopInRoot <= 0) return;
        dockTop = dockTopInRoot;
        place();
    }

    private void place() {
        float effectiveDockTop = dockTop > 0 ? dockTop
                : ctx.getResources().getDisplayMetrics().heightPixels - 190 * density;
        if (section.getHeight() > 0) {
            section.setY(Math.max(0, effectiveDockTop - 72 * density - section.getHeight()));
        }
        if (dots.getHeight() > 0) {
            dots.setY(Math.max(0, effectiveDockTop - 32 * density - dots.getHeight()));
        }
    }

    /** Hidden at once (Resume never stays focusable under the rows), faded back in at the top again. */
    void setHiddenByScroll(boolean hidden) {
        if (hidden == hiddenByScroll) return;
        hiddenByScroll = hidden;
        if (hidden) trailer.stop();
        updateDotsVisibility();
        followGlass(400);
        if (drift != null) {
            if (hidden) drift.pause();
            else if (!paused) drift.resume();
        }
        art.animate().cancel();
        putUpPendingArt();
        if (art.getDrawable() != null) art.animate().alpha(restingArtAlpha()).setDuration(300).start();
        if (items.isEmpty() || selection.visible() == null) return;
        section.animate().cancel();
        if (hidden) {
            section.setVisibility(View.INVISIBLE);
        } else {
            section.setAlpha(0f);
            section.setVisibility(View.VISIBLE);
            section.animate().alpha(1f).setDuration(200).start();
        }
    }

    /** Focus Resume, on the slide showing now. */
    void focus() {
        if (isShown()) resume.requestFocus();
    }

    /** Home went to the background: no slides advance, so no pictures are fetched, while nobody sees them. */
    void pause() {
        paused = true;
        trailer.stop();
        ++refreshGeneration;
        main.removeCallbacks(advance);
        if (drift != null) drift.pause();
    }

    /** Re-reads the list off the main thread; the shelf is rebuilt only when the focused app's share changed. */
    void refresh() {
        paused = false;
        followGlass(0);
        scheduleAdvance();
        if (drift != null && !hiddenByScroll) drift.resume();
        loadTrending(false);
        final int refresh = ++refreshGeneration;
        new Thread(() -> {
            List<Item> loaded = load(ctx);
            main.post(() -> {
                if (refresh != refreshGeneration) return;
                allItems = loaded;
                if (!"__trending__".equals(app)) rebuild();
                warmDockApps(loaded);
            });
        }, TAG).start();
    }

    void loadTrending(boolean showWhenReady) {
        TitleMetadata.trending(ctx, list -> {
            trendingItems.clear();
            if (list != null) trendingItems.addAll(list);
            if (showWhenReady && "__trending__".equals(app)) {
                rebuildTrending();
            }
        });
    }

    void showHomeTrending() {
        app = "__trending__";
        appArtwork = null;
        if (trendingItems.isEmpty()) {
            loadTrending(true);
        } else {
            rebuildTrending();
        }
    }

    private void rebuildTrending() {
        if (!"__trending__".equals(app)) return;
        StringBuilder key = new StringBuilder("__trending__\n");
        for (Item item : trendingItems) key.append(item.intentUri).append('|').append(item.artUri).append('\n');
        if (key.toString().equals(shownKey)) {
            restoreFocusWhenReady = true;
            if (isShown()) focus();
            return;
        }
        shownKey = key.toString();
        restoreFocusWhenReady = true;
        selection.clear();
        holdOldArt();   // before the trailer stops, so its frame is still there to keep
        trailer.stop();
        pendingSlide = null;
        pendingArt = null;
        art.animate().cancel();
        if (drift != null) drift.cancel();
        art.setImageDrawable(null);
        section.setAlpha(0f);
        items.clear();
        items.addAll(trendingItems);
        dots.removeAllViews();
        dots.setVisibility(View.GONE);
        int size = Math.round(5 * density);
        for (int i = 0; i < items.size() && items.size() > 1; i++) {
            View dot = new View(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.leftMargin = Math.round(7 * density);
            dots.addView(dot, lp);
        }
        section.setVisibility(View.GONE);
        scrim.setAlpha(1f);
        if (items.isEmpty()) {
            selection.clear();
            pendingArt = null;
            art.animate().alpha(0f).setDuration(200).start();
            LiquidGlass.setBackdrop(null);
            releaseOldArt();
            host.leaveDown();
        } else {
            select(0);
        }
        scheduleAdvance();
    }

    /**
     * The dock's focus moved to {@code packageName}: the shelf shows that app's programmes, as the Top Shelf does on
     * tvOS, or its artwork softened across the screen when it published none.
     */
    void showApp(String packageName, Drawable artwork) {
        if (packageName == null || (packageName.equals(app) && !"__trending__".equals(app))) return;
        app = packageName;
        appArtwork = artwork;
        rebuild();
    }

    private void rebuild() {
        // The app's Watch Next rows; only when it has none, its home screen channels (load puts those last).
        List<Item> mine = new ArrayList<>();
        for (Item item : allItems) {
            if (mine.size() == APP_MAX_ITEMS || (item.channel && !mine.isEmpty() && !mine.get(0).channel)) break;
            if (item.packageName != null && item.packageName.equals(app)) mine.add(item);
        }
        StringBuilder key = new StringBuilder(String.valueOf(app)).append('\n');
        for (Item item : mine) key.append(item.intentUri).append('|').append(item.positionMs)
                .append('|').append(item.durationMs).append('|').append(item.artUri).append('|').append(item.title)
                .append('|').append(item.subtitle).append('|').append(item.season).append('|').append(item.episode).append('\n');
        if (key.toString().equals(shownKey)) return;
        shownKey = key.toString();
        boolean hadFocus = controlsFocused();
        restoreFocusWhenReady = hadFocus;
        selection.clear();
        holdOldArt();   // before the trailer stops, so its frame is still there to keep
        trailer.stop();
        pendingSlide = null;
        pendingArt = null;
        art.animate().cancel();
        if (drift != null) drift.cancel();
        art.setImageDrawable(null);
        section.setAlpha(0f);
        items.clear();
        items.addAll(mine);
        dots.removeAllViews();
        dots.setVisibility(View.GONE);
        int size = Math.round(5 * density);
        for (int i = 0; i < items.size() && items.size() > 1; i++) {
            View dot = new View(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.leftMargin = Math.round(7 * density);
            dots.addView(dot, lp);
        }
        boolean empty = items.isEmpty();
        section.setVisibility(View.GONE);
        scrim.setAlpha(1f);
        if (empty) {
            selection.clear();
            Bitmap soft = HeroArt.from(appArtwork);
            artAlpha = APP_ART_ALPHA;
            if (soft != null) setArt(soft);
            else {
                pendingArt = null;   // nothing to show now: a picture still dipping in would be the old app's
                art.animate().alpha(0f).setDuration(200).start();
                LiquidGlass.setBackdrop(null);
                releaseOldArt();
            }
        } else {
            select(0);
        }
        scheduleAdvance();
        if (hadFocus) {
            if (empty) host.leaveDown();
            else focus();
        }
    }

    private void scheduleAdvance() {
        main.removeCallbacks(advance);
        if (!paused && items.size() > 1 && !trailer.busy()) main.postDelayed(advance, ADVANCE_MS);
    }

    /**
     * The trailer played to its end (or its time limit): straight on to the next slide, as Apple TV does, even with
     * focus on Play; a slide whose preview failed waits the usual ADVANCE_MS instead (TrailerBackdrop's finished).
     */
    private void trailerEnded() {
        if (items.size() > 1 && !paused && !hiddenByScroll) select(pageTarget(selected, 1, items.size()));
        else scheduleAdvance();
    }

    /** Shows slide {@code index}: its artwork across the screen, its title, its progress and its dot. */
    private void select(int index) {
        if (index < 0 || index >= items.size()) return;
        selected = index;
        trailer.stop();
        Item item = items.get(index);
        final int request = selection.begin();
        main.removeCallbacks(advance);
        art.animate().cancel();
        pendingSlide = null;
        pendingArt = null;
        if (art.getDrawable() != null) art.setAlpha(restingArtAlpha());
        Slide prepared = new Slide(item, null, index);
        Runnable ready = () -> {
            if (!prepared.artworkReady || !prepared.titleReady || !selection.isCurrent(request)) return;
            // Hold the swap until the glass copy of the new picture exists too, so the backdrop and the dock change
            // in the same frame (at once when it is already blurred, as on a second visit).
            LiquidGlass.prepare(prepared.artwork, () -> {
                if (!selection.isCurrent(request)) return;
                pendingSlide = prepared;
                pendingRequest = request;
                followGlass(700);
                // Apple TV's carousel: the slide and its text move out the way you page, the next one glides in
                // from the other side. The drift waits until it has landed.
                boolean slide = motion && art.getDrawable() != null;
                float shift = SLIDE_DP * density * slideDir;
                slideDir = 1;
                art.animate().cancel();
                section.animate().cancel();
                section.animate().alpha(0f).translationX(-shift / 2).setInterpolator(SLIDE_OUT)
                        .setDuration(slide ? 140 : 0).start();
                art.animate().alpha(0f).translationX(-shift).setInterpolator(SLIDE_OUT)
                        .setUpdateListener(a -> LiquidGlass.frame()).setDuration(slide ? 140 : 0)
                        .withEndAction(() -> {
                            putUpPendingArt();
                            if (drift != null) drift.cancel();
                            art.setTranslationX(shift);
                            section.setAlpha(0f);
                            section.setTranslationX(shift / 2);
                            section.animate().alpha(1f).translationX(0f).setInterpolator(SLIDE_IN)
                                    .setDuration(slide ? 320 : 0).start();
                            art.animate().alpha(restingArtAlpha()).translationX(0f).setInterpolator(SLIDE_IN)
                                    .setDuration(slide ? 320 : 0).withEndAction(this::startDrift).start();
                        }).start();
            });
        };
        ArtCallback artworkReady = bitmap -> {
            prepared.artwork = bitmap;
            prepared.artworkReady = true;
            ready.run();
        };
        if (item.artUri == null || item.artUri.isEmpty()) artworkReady.onArt(null);
        else loadArt(item.artUri, artworkReady);
        TitleMetadata.load(ctx, item, data -> {
            if (!selection.isCurrent(request)) return;
            prepared.details = data;
            String uri = data == null ? null : data.optString("logo", null);
            if (uri == null || uri.isEmpty()) uri = titleLogoUri(item);
            if (uri == null || unavailableLogos.contains(uri)) {
                prepared.titleReady = true;
                ready.run();
                return;
            }
            String logoUri = uri;
            loadImage(uri, 800, true, bitmap -> {
                if (bitmap == null) unavailableLogos.add(logoUri);
                prepared.logo = bitmap;
                prepared.titleReady = true;
                ready.run();
            });
        });
    }

    /** Called only when the matching artwork is ready (or has explicitly failed). */
    private void renderDetails(Item item, int index) {
        title.setText(item.title);
        String line = !TextUtils.isEmpty(item.subtitle) ? item.subtitle : "";
        subtitle.setText(line);
        subtitle.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        String episode = episodeLabel(item.season, item.episode);
        String source = item.packageName == null ? "" : item.packageName;
        try {
            source = ctx.getPackageManager().getApplicationLabel(
                    ctx.getPackageManager().getApplicationInfo(source, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { source = ""; }
        if ("__trending__".equals(app)) {
            appIcon.setVisibility(View.GONE);
            metadata.setText(item.programType == 1 ? "TV Show" : "Movie");
            metadata.setVisibility(View.VISIBLE);
        } else {
            metadata.setText(source);
            metadata.setVisibility(source.isEmpty() ? View.GONE : View.VISIBLE);
            android.graphics.drawable.Drawable icon = null;
            try {
                if (item.packageName != null) icon = ctx.getPackageManager().getApplicationIcon(item.packageName);
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // no icon: the row starts with the text
            }
            appIcon.setImageDrawable(icon);
            appIcon.setVisibility(icon == null ? View.GONE : View.VISIBLE);
        }
        // TMDB's label and rating come with the details (showTitleLogo); none until then.
        badge.setVisibility(View.GONE);
        rating.setVisibility(View.GONE);
        nextButton.setVisibility(items.size() > 1 ? View.VISIBLE : View.GONE);
        String action = ctx.getString(item.positionMs > 0 ? R.string.resume : R.string.play);
        if (!episode.isEmpty()) action += "  " + episode;
        resume.setText("▶  " + action);
        resume.setContentDescription(action);
        progressRow.setVisibility(item.durationMs > 0 ? View.VISIBLE : View.GONE);
        section.setVisibility(hiddenByScroll ? View.INVISIBLE : View.VISIBLE);
        section.setAlpha(1f);
        if (restoreFocusWhenReady) {
            restoreFocusWhenReady = false;
            if (!hiddenByScroll && !paused) resume.post(resume::requestFocus);
        }
        boolean timed = item.durationMs > 0;
        ((View) progressFill.getParent()).setVisibility(timed && item.positionMs > 0 ? View.VISIBLE : View.GONE);
        timeLeft.setVisibility(timed ? View.VISIBLE : View.GONE);
        if (timed) {
            float done = Math.max(0f, Math.min(1f, (float) item.positionMs / item.durationMs));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) progressFill.getLayoutParams();
            lp.weight = done;
            progressFill.setLayoutParams(lp);
            timeLeft.setText(item.positionMs > 0 ? ctx.getString(R.string.time_left, timeLeft(item)) : timeLeft(item));
        }
        for (int i = 0; i < dots.getChildCount(); i++) {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(3 * density);
            d.setColor(i == index ? 0xFFFFFFFF : 0x66FFFFFF);
            View dot = dots.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            lp.width = Math.round((i == index ? 14 : 5) * density);
            dot.setLayoutParams(lp);
            dot.setBackground(d);
        }
        updateDotsVisibility();
        place();
        scheduleAdvance();
    }

    private void updateDotsVisibility() {
        dots.setVisibility(!hiddenByScroll && items.size() > 1 && selection.visible() != null
                ? View.VISIBLE : View.GONE);
    }

    static String episodeLabel(String season, String episode) {
        String s = season == null ? "" : season.trim();
        String e = episode == null ? "" : episode.trim();
        if (!s.isEmpty() && s.matches("[0-9]+")) s = "S" + s;
        if (!e.isEmpty() && e.matches("[0-9]+")) e = "E" + e;
        return s.isEmpty() ? e : e.isEmpty() ? s : s + ", " + e;
    }

    /** All fields were prepared before committing the slide; no visible title replacement. */
    private void showTitleLogo(Slide slide) {
        titleLogo.setImageDrawable(null);
        titleLogo.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        org.json.JSONObject data = slide.details;
        if (data != null) {
            String english = data.optString("title");
            if ("net.mbc.shahidTV".equals(slide.item.packageName)) {
                if (!english.isEmpty() && !TitleMatch.arabic(english)) title.setText(english);
            }
            String overview = data.optString("overview");
            if (("net.mbc.shahidTV".equals(slide.item.packageName) || TextUtils.isEmpty(slide.item.subtitle))
                    && !overview.isEmpty() && !TitleMatch.arabic(overview)) {
                subtitle.setText(overview);
                subtitle.setVisibility(View.VISIBLE);
            }
            String details = data.optString("metadata");
            if (!details.isEmpty()) { metadata.setText(details); metadata.setVisibility(View.VISIBLE); }
            String label = data.optString("badge");
            badge.setText(label);
            badge.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
            String age = data.optString("rating");
            rating.setText(age);
            rating.setVisibility(age.isEmpty() ? View.GONE : View.VISIBLE);
        }
        Bitmap bitmap = slide.logo;
        if (bitmap != null) {
            float ratio = (float) bitmap.getWidth() / Math.max(1, bitmap.getHeight());
            float scale = Math.min(320 * density / bitmap.getWidth(), 76 * density / bitmap.getHeight());
            float scaledHeight = bitmap.getHeight() * scale;
            if (ratio > 5.5f || scaledHeight < 34 * density) {
                titleLogo.setVisibility(View.GONE);
                title.setVisibility(View.VISIBLE);
            } else {
                ViewGroup.LayoutParams lp = titleLogo.getLayoutParams();
                lp.width = Math.max(1, Math.round(bitmap.getWidth() * scale));
                lp.height = Math.max(1, Math.round(bitmap.getHeight() * scale));
                titleLogo.setLayoutParams(lp);
                titleLogo.setImageBitmap(bitmap);
                titleLogo.setContentDescription(title.getText());
                titleLogo.setVisibility(View.VISIBLE);
                title.setVisibility(View.GONE);
            }
        }
        place();
    }

    private static final java.util.regex.Pattern METAHUB_TITLE = java.util.regex.Pattern.compile(
            "https://images\\.metahub\\.space/(?:background|poster)/(?:small|medium|large)/(tt[0-9]{5,12})/img(?:\\?.*)?");
    private static final java.util.regex.Pattern IMDB_TITLE = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9])(tt[0-9]{5,12})(?![A-Za-z0-9])");

    static String titleLogoUri(Item item) {
        java.util.regex.Matcher match = METAHUB_TITLE.matcher(item.artUri == null ? "" : item.artUri);
        if (match.matches()) return "https://images.metahub.space/logo/medium/" + match.group(1) + "/img";
        if (!"com.stremio.one".equals(item.packageName) && !"com.stremio".equals(item.packageName)) return null;
        match = IMDB_TITLE.matcher(item.intentUri == null ? "" : item.intentUri);
        return match.find() ? "https://images.metahub.space/logo/medium/" + match.group(1) + "/img" : null;
    }

    private float restingArtAlpha() {
        return hiddenByScroll ? Math.min(artAlpha, SCROLLED_ART_ALPHA) : artAlpha;
    }

    /** A short crossfade to {@code bmp}: dip and come back with the new picture, as the shelf does on tvOS. */
    private void setArt(Bitmap bmp) {
        followGlass(800);   // the dip and the fade back in
        art.animate().cancel();
        pendingArt = bmp;
        LiquidGlass.prepare(bmp);   // blurred during the dip, ready for the swap
        art.animate().alpha(0f).setDuration(art.getDrawable() == null ? 0 : 150).withEndAction(() -> {
            putUpPendingArt();
            art.animate().alpha(restingArtAlpha()).setDuration(300).start();
        }).start();
    }

    /**
     * Shows the picture waiting for its dip to end. Anything that cancels the dip (another fade on the backdrop) calls
     * this first: a cancelled animation skips its end action, which left the old picture under the new title.
     */
    private void putUpPendingArt() {
        if (pendingSlide != null) {
            Slide slide = pendingSlide;
            pendingSlide = null;
            if (!selection.commit(pendingRequest, slide)) return;
            artAlpha = 1f;
            art.setImageBitmap(slide.artwork);
            LiquidGlass.setBackdrop(slide.artwork);
            releaseOldArt();
            renderDetails(slide.item, slide.index);
            showTitleLogo(slide);
            if (!paused && !hiddenByScroll) {
                String homepage = slide.details == null ? null : slide.details.optString("homepage");
                org.json.JSONArray videos = slide.details == null ? null : slide.details.optJSONArray("youtube_trailers");
                String[] ids = new String[videos == null ? 0 : Math.min(3, videos.length())];
                for (int i = 0; i < ids.length; i++) ids[i] = videos.optString(i);
                trailer.show(TrailerSource.page(slide.item.intentUri, slide.item.programType == 0, homepage), ids);
                prefetchAround(slide.index);
                scheduleAdvance();
            }
            startDrift();
            return;
        }
        if (pendingArt == null) return;
        art.setImageBitmap(pendingArt);
        LiquidGlass.setBackdrop(pendingArt);
        releaseOldArt();
        pendingArt = null;
        startDrift();
    }

    /**
     * The slides either side of {@code index}, decoded ahead (picture and title logo) so paging to them swaps at once,
     * and the next one's trailer fetched too (TrailerBackdrop.prewarm).
     */
    private void prefetchAround(int index) {
        if (items.size() < 2) return;
        Item previous = items.get((index - 1 + items.size()) % items.size());
        Item next = items.get((index + 1) % items.size());
        for (Item item : new Item[]{previous, next}) {
            if (item.artUri != null && !item.artUri.isEmpty()) loadArt(item.artUri, bmp -> {});
            TitleMetadata.load(ctx, item, data -> {
                String uri = data == null ? null : data.optString("logo", null);
                if (uri == null || uri.isEmpty()) uri = titleLogoUri(item);
                if (uri != null && !unavailableLogos.contains(uri)) loadImage(uri, 800, true, bmp -> {});
            });
        }
        TitleMetadata.load(ctx, next, data -> {
            if (data == null) return;
            org.json.JSONArray videos = data.optJSONArray("youtube_trailers");
            String[] ids = new String[videos == null ? 0 : Math.min(3, videos.length())];
            for (int i = 0; i < ids.length; i++) ids[i] = videos.optString(i);
            trailer.prewarm(TrailerSource.page(next.intentUri, next.programType == 0, data.optString("homepage")), ids);
        });
    }

    /**
     * The first slide of every app in the list, onto the disk cache only (no decode, nothing kept in memory), so moving
     * along the dock to an app not visited yet does not wait on the network.
     */
    private void warmDockApps(List<Item> loaded) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Item item : loaded) {
            if (item.packageName == null || !seen.add(item.packageName)) continue;
            String art = item.artUri;
            TitleMetadata.load(ctx, item, data -> {
                String logo = data == null ? null : data.optString("logo", null);
                new Thread(() -> { warm(art); warm(logo); }, TAG).start();
            });
        }
    }

    /** Read an http(s) image through to the disk cache and drop it. */
    private static void warm(String uri) {
        if (uri == null || !uri.startsWith("http")) return;
        try (InputStream in = new URL(uri).openStream()) {
            byte[] buf = new byte[16 * 1024];
            while (in.read(buf) > 0) { /* the cache keeps it */ }
        } catch (IOException | RuntimeException ignored) {
            // only a warm-up: the slide fetches it again when shown
        }
    }

    /**
     * Moving to another app (or from the suggestions to one): the picture on screen, or the trailer's frame, stays on
     * top until the new one is up and then fades off it (releaseOldArt), a crossfade instead of a dip to black.
     */
    private void holdOldArt() {
        Bitmap frame = trailer.frame();
        Drawable old = art.getDrawable();
        if (frame == null && (old == null || art.getAlpha() == 0f)) return;
        if (fade == null) {
            fade = new ImageView(ctx);
            fade.setScaleType(ImageView.ScaleType.CENTER_CROP);
            fade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ViewGroup parent = (ViewGroup) art.getParent();
            parent.addView(fade, parent.indexOfChild(art) + 1, new ViewGroup.LayoutParams(-1, -1));
        }
        fade.animate().cancel();
        boolean video = frame != null;
        if (video) fade.setImageBitmap(frame);
        else fade.setImageDrawable(old);
        fade.setScaleX(video ? 1f : art.getScaleX());
        fade.setScaleY(video ? 1f : art.getScaleY());
        fade.setTranslationX(video ? 0f : art.getTranslationX());
        fade.setAlpha(video ? 1f : art.getAlpha());
        fade.setVisibility(View.VISIBLE);
    }

    private void releaseOldArt() {
        if (fade == null || fade.getVisibility() != View.VISIBLE) return;
        fade.animate().alpha(0f).setDuration(motion ? 450 : 0).withEndAction(() -> {
            fade.setVisibility(View.GONE);
            fade.setImageDrawable(null);
        }).start();
    }

    /** Make the glass follow the backdrop for at least {@code ms}, and for as long as the drift runs. */
    private void followGlass(long ms) {
        if (!LiquidGlass.enabled) return;
        glassFollowUntil = Math.max(glassFollowUntil, android.os.SystemClock.uptimeMillis() + ms);
        main.removeCallbacks(glassTick);
        main.post(glassTick);
    }

    /**
     * Ken Burns: the backdrop slowly grows and drifts sideways while its slide shows, alternating direction, the
     * nearest thing to tvOS's video previews (apps here publish none). The scale always covers the drift, so no
     * edge shows. Off with reduced motion; paused while the grid is scrolled or Home is in the background.
     */
    private void startDrift() {
        if (drift != null) drift.cancel();
        art.setScaleX(1f);
        art.setScaleY(1f);
        art.setTranslationX(0f);
        if (!motion) return;
        driftLeft = !driftLeft;
        float dx = (driftLeft ? -1 : 1) * 20 * density;
        drift = new AnimatorSet();
        drift.playTogether(ObjectAnimator.ofFloat(art, View.SCALE_X, 1f, DRIFT_SCALE),
                ObjectAnimator.ofFloat(art, View.SCALE_Y, 1f, DRIFT_SCALE),
                ObjectAnimator.ofFloat(art, View.TRANSLATION_X, 0f, dx));
        drift.setDuration(ADVANCE_MS + 2000);   // a little longer than the slide, so it never visibly stops
        drift.setInterpolator(new LinearInterpolator());
        drift.start();
        followGlass(0);
        if (paused || hiddenByScroll) drift.pause();
    }

    interface ArtCallback { void onArt(Bitmap bmp); }

    /** From the cache at once, or decoded off the main thread; {@code done} runs on the main thread, with null when decoding fails. */
    private void loadArt(String uri, ArtCallback done) {
        loadImage(uri, SHELF_ART_PX, false, done);
    }

    private void loadImage(String uri, int maxWidth, boolean alpha, ArtCallback done) {
        Bitmap cached = CACHE.get(uri);
        if (cached != null) {
            done.onArt(cached);
            return;
        }
        new Thread(() -> {
            Bitmap bmp = decode(ctx, uri, maxWidth, alpha);
            if (bmp != null) CACHE.put(uri, bmp);
            main.post(() -> done.onArt(bmp));
        }, TAG).start();
    }

    /** Time left, as 28:15 or 1:02:40; the full length when nothing was watched yet. */
    static String timeLeft(Item item) {
        long s = Math.max(0, item.durationMs - item.positionMs) / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void open(Item item) {
        Slide shown = selection.visible();
        String uriStr = item.intentUri;
        if (shown != null && shown.details != null && uriStr != null && uriStr.contains("stremio:///detail/")) {
            String imdb = shown.details.optString("imdb_id", null);
            if (imdb != null && !imdb.isEmpty()) {
                String type = item.programType == 1 || uriStr.contains("/series/") ? "series" : "movie";
                uriStr = "stremio:///detail/" + type + "/" + imdb;
            }
        }
        Intent intent = null;
        try {
            intent = Intent.parseUri(uriStr, Intent.URI_INTENT_SCHEME);
        } catch (java.net.URISyntaxException ignored) {
            // fall back to the app below
        }
        if (intent == null && item.packageName != null) {
            intent = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(item.packageName);
        }
        if (intent == null) return;
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "cannot open " + item.packageName + ": " + e);
        }
    }

    /**
     * Watch Next rows, newest first, then the apps' home screen channel programmes (for apps such as Stremio that
     * publish their "Continue watching" as a channel instead), only browsable rows with something to open, from the
     * apps in continue_watching_apps (all apps when that list is empty). Call off the main thread.
     */
    static List<Item> load(Context ctx) {
        List<Item> items = new ArrayList<>();
        List<String> apps = Arrays.asList(ctx.getResources().getStringArray(R.array.continue_watching_apps));
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, PROJECTION, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            while (c != null && c.moveToNext() && items.size() < MAX_ITEMS) {
                Item item = read(c, apps, false);
                if (item != null) items.add(item);
            }
        } catch (RuntimeException e) {
            // No permission (SecurityException) or a provider that is missing or disabled: no shelf, nothing else.
            Log.w(TAG, "watch next unavailable: " + e);
        }
        items.addAll(loadChannels(ctx, apps));
        return items;
    }

    /** Channel programmes, a channel called "Continue watching" first, then by channel and the app's own order. */
    private static List<Item> loadChannels(Context ctx, List<String> apps) {
        List<Long> continueChannels = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(1) == null ? "" : c.getString(1).toLowerCase(Locale.ROOT);
                if (name.contains("continue") || name.contains("متابعة")) continueChannels.add(c.getLong(0));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "channels unavailable: " + e);
        }
        String[] projection = Arrays.copyOf(PROJECTION, PROJECTION.length + 2);
        projection[PROJECTION.length] = TvContract.PreviewPrograms.COLUMN_CHANNEL_ID;
        projection[PROJECTION.length + 1] = TvContract.PreviewPrograms.COLUMN_WEIGHT;
        List<Item> first = new ArrayList<>(), rest = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, projection, null, null,
                TvContract.PreviewPrograms.COLUMN_CHANNEL_ID + ", " + TvContract.PreviewPrograms.COLUMN_WEIGHT + " DESC")) {
            while (c != null && c.moveToNext() && first.size() + rest.size() < 4 * MAX_ITEMS) {
                Item item = read(c, apps, true);
                if (item != null) (continueChannels.contains(c.getLong(PROJECTION.length)) ? first : rest).add(item);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "channel programmes unavailable: " + e);
        }
        first.addAll(rest);
        return first;
    }

    /** One row of either table (same column names), or null when it is hidden, unopenable or from another app. */
    private static Item read(Cursor c, List<String> apps, boolean channel) {
        if (c.getInt(7) == 0 || c.getString(6) == null) return null;
        if (!apps.isEmpty() && !apps.contains(c.getString(3))) return null;
        // The thumbnail is the landscape still; posters are often portrait (Stremio), which crops badly across a TV.
        String art = sharper(c.getString(5) != null ? c.getString(5) : c.getString(4));
        String line = c.getString(2) != null ? c.getString(2) : c.getString(1);
        return new Item(c.getString(0), line, c.getString(3), art, c.getString(6), c.getLong(8), c.getLong(9), channel, c.getString(10), c.getString(11), c.getString(12), c.isNull(13) ? -1 : c.getInt(13));
    }

    private static final java.util.regex.Pattern WIDTH = java.util.regex.Pattern.compile("([?&]width=)(\\d+)");
    private static final java.util.regex.Pattern HEIGHT = java.util.regex.Pattern.compile("([?&]height=)(\\d+)");

    /**
     * Asks the image server for a picture big enough to fill the screen. Apps publish card-sized artwork: Shahid's
     * URLs carry ?width=400&height=225, Stremio's (metahub) a small or medium size in the path.
     */
    static String sharper(String uri) {
        if (uri == null || !uri.startsWith("http")) return uri;
        java.util.regex.Matcher w = WIDTH.matcher(uri), h = HEIGHT.matcher(uri);
        if (w.find() && h.find()) {
            int width = Integer.parseInt(w.group(2)), height = Integer.parseInt(h.group(2));
            if (width > 0 && width < SHELF_ART_PX) {
                int newHeight = Math.round((float) height * SHELF_ART_PX / width);
                uri = WIDTH.matcher(uri).replaceFirst("$1" + SHELF_ART_PX);
                uri = HEIGHT.matcher(uri).replaceFirst("$1" + newHeight);
            }
        }
        return uri.replace("metahub.space/background/medium/", "metahub.space/background/large/")
                .replace("metahub.space/background/small/", "metahub.space/background/large/");
    }

    /** content://, android.resource:// or http(s) artwork, downsampled to about {@code widthPx}. */
    private static Bitmap decode(Context ctx, String uri, int widthPx, boolean alpha) {
        byte[] bytes;
        try (InputStream in = open(ctx, uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                // ponytail: 4 MB cap per image keeps a huge poster from eating the TV's memory; stream-decode if needed.
                if (out.size() > 4 * 1024 * 1024) return null;
            }
            bytes = out.toByteArray();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Image unavailable (" + Uri.parse(uri).getHost() + "): " + e.getClass().getSimpleName());
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= widthPx) sample *= 2;
        opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    private static InputStream open(Context ctx, String uri) throws IOException {
        String scheme = Uri.parse(uri).getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            return conn.getInputStream();
        }
        return ctx.getContentResolver().openInputStream(Uri.parse(uri));
    }
}
