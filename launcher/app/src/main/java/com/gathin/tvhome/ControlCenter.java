package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** Compact floating glass controls, with no profile header or surrounding card. */
final class ControlCenter {
    static final class Action {
        final int icon;
        final String label;
        final Runnable run;
        Action(int icon, String label, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.run = run;
        }
    }

    private static final String FIRST_CONTROL = "first_control";
    private ControlCenter() {}

    static int widthPx(float density) { return Math.round(280 * density); }

    static void focusFirst(View panel) {
        View first = panel.findViewWithTag(FIRST_CONTROL);
        if (first != null) first.requestFocus();
    }

    static LinearLayout build(Context ctx, List<Action> actions, Runnable close, float density) {
        Config config = Config.load(ctx);
        boolean backdrop = ctx instanceof MainActivity || MainActivity.homeVisible;
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5, density), dp(5, density), dp(5, density), dp(5, density));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        for (int i = 0; i < actions.size(); i += 2) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52, density));
            if (i > 0) rowLp.topMargin = dp(8, density);
            panel.addView(row, rowLp);
            for (int col = 0; col < 2 && i + col < actions.size(); col++) {
                View tile = button(ctx, actions.get(i + col), close, density, config, backdrop);
                if (i + col == 0) tile.setTag(FIRST_CONTROL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (col > 0) lp.setMarginStart(dp(8, density));
                row.addView(tile, lp);
            }
        }
        return panel;
    }

    private static View button(Context ctx, Action action, Runnable close, float density, Config config, boolean backdrop) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(9, density), 0, dp(9, density), 0);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(action.label);

        FrameLayout circle = new FrameLayout(ctx);
        ImageView glyph = new ImageView(ctx);
        glyph.setImageResource(action.icon);
        circle.addView(glyph, new FrameLayout.LayoutParams(dp(19, density), dp(19, density), Gravity.CENTER));
        tile.addView(circle, new LinearLayout.LayoutParams(dp(32, density), dp(32, density)));
        TextView label = new TextView(ctx);
        label.setText(action.label);
        label.setTextSize(11);
        label.setTypeface(UiFont.get(ctx, R.string.font_medium));
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMarginStart(dp(7, density));
        tile.addView(label, labelLp);
        boolean glassEnabled = ctx.getResources().getBoolean(R.bool.liquid_glass);
        Drawable resting = glassEnabled ? new LiquidGlass.GlassDrawable(tile, dp(23, density),
                backdrop ? 0x08FFFFFF : 0x50141419, backdrop, true) : null;
        Drawable focusedGlass = glassEnabled ? new LiquidGlass.GlassDrawable(tile, dp(23, density),
                0x22FFFFFF, backdrop, true) : null;
        Motion.Focus motion = new Motion.Focus(tile, config.focusOvershoot);
        style(tile, circle, glyph, label, false, density, resting);
        tile.setOnFocusChangeListener((v, focused) -> {
            style(tile, circle, glyph, label, focused, density, focused ? focusedGlass : resting);
            if (config.animations && !config.reducedMotion) {
                motion.to(focused ? 1.025f : 1f, focused ? -density / 2f : 0f, focused ? 6 * density : 0f);
            }
        });
        tile.setOnClickListener(v -> { close.run(); action.run.run(); });
        return tile;
    }

    private static void style(View tile, View circle, ImageView glyph, TextView label,
                              boolean focused, float density, Drawable glassSurface) {
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                focused ? new int[]{0xFAFFFFFF, 0xE8EEEEF2} : new int[]{0xE6404046, 0xE6212128});
        glass.setCornerRadius(dp(23, density));
        glass.setStroke(Math.max(1, dp(0.7f, density)), focused ? 0xFFFFFFFF : 0x55FFFFFF);
        tile.setBackground(glassSurface != null ? glassSurface : glass);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(focused ? 0x12FFFFFF : 0x10FFFFFF);
        circle.setBackground(iconBg);
        glyph.setImageTintList(ColorStateList.valueOf(glassSurface != null || !focused ? 0xFFFFFFFF : 0xFF202024));
        label.setTextColor(glassSurface != null || !focused ? 0xFFFFFFFF : 0xFF202024);
        label.setShadowLayer(glassSurface != null ? density : 0f, 0f, density / 2f, 0xA6000000);
    }

    private static int dp(float value, float density) { return Math.round(value * density); }
}
