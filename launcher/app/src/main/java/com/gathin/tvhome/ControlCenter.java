package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.widget.TextClock;
import java.lang.ref.WeakReference;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.graphics.Typeface;
import android.provider.Settings;
import android.content.ActivityNotFoundException;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Independent glass controls; the tall power card anchors a compact two-column layout. */
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
    private static final String PROFILE_CONTROL = "profile_control";
    private static final String HOME_CONTROL = "home_control";
    static final String HOME_BACKDROP = "home_backdrop";
    private static WeakReference<View> homePill = new WeakReference<>(null);
    private ControlCenter() {}

    static void registerHomePill(View pill) { homePill = new WeakReference<>(pill); }

    /** The collapsed pill stays in layout so returning from either kind of panel never moves the header. */
    static void setExpanded(boolean expanded) {
        View pill = homePill.get();
        if (pill == null) return;
        pill.animate().cancel();
        pill.setImportantForAccessibility(expanded ? View.IMPORTANT_FOR_ACCESSIBILITY_NO
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        pill.setAlpha(expanded ? 0f : 1f);
    }

    static int widthPx(float density) { return Math.round(234 * density); }

    static void focusFirst(View panel) {
        View first = panel.findViewWithTag(FIRST_CONTROL);
        if (first != null) first.requestFocus();
    }

    static LinearLayout build(Context ctx, List<Action> actions, Runnable close, float density) {
        Config config = Config.load(ctx);
        boolean backdrop = ctx instanceof MainActivity || MainActivity.homeVisible
                || ctx instanceof QuickSettingsActivity
                && ((QuickSettingsActivity) ctx).getIntent().getBooleanExtra(HOME_BACKDROP, false);
        LinearLayout panel = column(ctx);
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        panel.setPadding(dp(5, density), dp(5, density), dp(5, density), dp(5, density));
        panel.addView(header(ctx, close, density), rowParams(36, 0, density));
        List<Action> remaining = new ArrayList<>(actions);
        Action power = take(remaining, R.drawable.ic_power);
        Action wifi = take(remaining, R.drawable.ic_wifi);
        Action bluetooth = take(remaining, R.drawable.ic_bluetooth);
        Action audio = take(remaining, R.drawable.ic_audio);
        Action inputs = take(remaining, R.drawable.ic_input);
        LinearLayout top = row(ctx);
        panel.addView(top, rowParams(108, 12, density));
        View powerView = power == null ? new View(ctx) : button(ctx, power, close, density, config, backdrop, 1);
        top.addView(powerView, cellParams(0, density));
        LinearLayout radios = column(ctx);
        top.addView(radios, cellParams(8, density));
        View wifiView = addRadio(radios, ctx, wifi, close, density, config, backdrop, 0);
        View bluetoothView = addRadio(radios, ctx, bluetooth, close, density, config, backdrop, 8);
        // Keep initial focus on the harmless connectivity card, away from the power action.
        if (wifi != null) wifiView.setTag(FIRST_CONTROL);
        else if (power != null) powerView.setTag(FIRST_CONTROL);
        LinearLayout middle = row(ctx);
        panel.addView(middle, rowParams(50, 8, density));
        View audioView = audio == null ? new View(ctx) : button(ctx, audio, close, density, config, backdrop, 0);
        View inputsView = inputs == null ? new View(ctx) : button(ctx, inputs, close, density, config, backdrop, 0);
        middle.addView(audioView, cellParams(0, density));
        middle.addView(inputsView, cellParams(8, density));
        List<View> shortcuts = new ArrayList<>();
        for (int i = 0; i < remaining.size(); i += 4) {
            LinearLayout bottom = row(ctx);
            panel.addView(bottom, rowParams(50, 8, density));
            for (int col = 0; col < 4 && i + col < remaining.size(); col++) {
                View tile = button(ctx, remaining.get(i + col), close, density, config, backdrop, 2);
                bottom.addView(tile, cellParams(col == 0 ? 0 : 8, density));
                shortcuts.add(tile);
            }
        }
        View avatar = panel.findViewWithTag(PROFILE_CONTROL);
        View home = panel.findViewWithTag(HOME_CONTROL);
        link(wifiView, avatar, View.FOCUS_UP);
        link(avatar, wifiView, View.FOCUS_DOWN);
        link(powerView, home, View.FOCUS_UP);
        link(home, powerView, View.FOCUS_DOWN);
        link(home, avatar, View.FOCUS_RIGHT);
        link(avatar, home, View.FOCUS_LEFT);
        link(powerView, wifiView, View.FOCUS_RIGHT);
        link(wifiView, powerView, View.FOCUS_LEFT);
        link(bluetoothView, powerView, View.FOCUS_LEFT);
        link(wifiView, bluetoothView, View.FOCUS_DOWN);
        link(bluetoothView, wifiView, View.FOCUS_UP);
        link(powerView, audioView, View.FOCUS_DOWN);
        link(audioView, powerView, View.FOCUS_UP);
        link(bluetoothView, inputsView, View.FOCUS_DOWN);
        link(inputsView, bluetoothView, View.FOCUS_UP);
        link(audioView, inputsView, View.FOCUS_RIGHT);
        link(inputsView, audioView, View.FOCUS_LEFT);
        for (int i = 0; i < shortcuts.size(); i++) {
            View tile = shortcuts.get(i);
            link(tile, i % 4 < 2 ? audioView : inputsView, View.FOCUS_UP);
        }
        if (!shortcuts.isEmpty()) {
            link(audioView, shortcuts.get(0), View.FOCUS_DOWN);
            link(inputsView, shortcuts.get(Math.min(2, shortcuts.size() - 1)), View.FOCUS_DOWN);
        }
        return panel;
    }

    private static Action take(List<Action> actions, int icon) {
        for (int i = 0; i < actions.size(); i++) if (actions.get(i).icon == icon) return actions.remove(i);
        return null;
    }

    private static LinearLayout column(Context ctx) {
        LinearLayout layout = row(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static LinearLayout row(Context ctx) {
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setClipChildren(false);
        layout.setClipToPadding(false);
        return layout;
    }

    private static LinearLayout.LayoutParams rowParams(int height, int gap, float density) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height, density));
        lp.topMargin = dp(gap, density);
        return lp;
    }

    private static LinearLayout.LayoutParams cellParams(int gap, float density) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.leftMargin = dp(gap, density);
        return lp;
    }

    private static View addRadio(LinearLayout parent, Context ctx, Action action, Runnable close,
            float density, Config config, boolean backdrop, int gap) {
        View tile = action == null ? new View(ctx) : button(ctx, action, close, density, config, backdrop, 0);
        parent.addView(tile, rowParams(50, gap, density));
        return tile;
    }

    private static void link(View from, View to, int direction) {
        if (!from.isFocusable() || !to.isFocusable()) return;
        if (direction == View.FOCUS_UP) from.setNextFocusUpId(to.getId());
        else if (direction == View.FOCUS_DOWN) from.setNextFocusDownId(to.getId());
        else if (direction == View.FOCUS_LEFT) from.setNextFocusLeftId(to.getId());
        else if (direction == View.FOCUS_RIGHT) from.setNextFocusRightId(to.getId());
    }

    /** kind: 0 = capsule, 1 = large power card, 2 = circular shortcut. */
    private static View button(Context ctx, Action action, Runnable close, float density,
            Config config, boolean backdrop, int kind) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setId(View.generateViewId());
        tile.setOrientation(kind == 1 ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(kind == 0 ? 10 : 4, density), 0, dp(kind == 0 ? 10 : 4, density), 0);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(action.label);
        if (kind == 2) tile.setTooltipText(action.label);
        ImageView glyph = new ImageView(ctx);
        glyph.setImageResource(action.icon);
        int iconSize = kind == 1 ? 54 : kind == 2 ? 25 : 23;
        tile.addView(glyph, new LinearLayout.LayoutParams(dp(iconSize, density), dp(iconSize, density)));
        TextView label = new TextView(ctx);
        label.setText(action.label);
        label.setTextSize(11);
        label.setTypeface(UiFont.get(ctx, R.string.font_medium));
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(kind == 1 ? Gravity.CENTER : Gravity.START);
        label.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        if (kind != 2) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(kind == 1
                    ? ViewGroup.LayoutParams.MATCH_PARENT : 0, ViewGroup.LayoutParams.WRAP_CONTENT, kind == 1 ? 0 : 1f);
            if (kind == 1) lp.topMargin = dp(8, density);
            else lp.leftMargin = dp(7, density);
            if (action.icon == R.drawable.ic_wifi) {
                label.setMaxLines(1);
                LinearLayout text = column(ctx);
                text.setGravity(Gravity.CENTER_VERTICAL);
                text.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView subtitle = new TextView(ctx);
                subtitle.setTextSize(9.5f);
                subtitle.setTypeface(UiFont.get(ctx, R.string.font_medium));
                subtitle.setSingleLine(true);
                subtitle.setEllipsize(TextUtils.TruncateAt.END);
                subtitle.setTag("wifi_subtitle");
                text.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                tile.addView(text, lp);
                bindWifi(tile, glyph, subtitle, action.label);
            } else tile.addView(label, lp);
        }
        float radius = dp(kind == 1 ? 27 : 25, density);
        Drawable resting = ctx.getResources().getBoolean(R.bool.liquid_glass)
                ? new LiquidGlass.GlassDrawable(tile, radius, backdrop ? 0x382A2D32 : 0x9930343B, backdrop, true)
                : null;
        GradientDrawable plain = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xBB484C52, 0xBB262A30});
        plain.setCornerRadius(radius);
        plain.setStroke(dp(0.6f, density), 0x40FFFFFF);
        Drawable rest = resting != null ? resting : plain;
        GradientDrawable selected = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFFFFF, 0xFFF0F1F3});
        selected.setCornerRadius(radius);
        if (action.icon == R.drawable.ic_bluetooth) bindBluetooth(tile, glyph);
        Motion.Focus motion = new Motion.Focus(tile, config.focusOvershoot);
        Runnable reset = () -> style(tile, glyph, label, false, rest, action.icon);
        reset.run();
        tile.setOnFocusChangeListener((v, focused) -> {
            style(tile, glyph, label, focused, focused ? selected : rest, action.icon);
            if (config.animations && !config.reducedMotion) {
                motion.to(focused ? 1.035f : 1f, 0f, focused ? 6 * density : 0f);
            }
        });
        if (action.icon == R.drawable.ic_wifi || action.icon == R.drawable.ic_bluetooth) {
            // Returning true consumes the hold, so releasing OK cannot also toggle the radio.
            tile.setOnLongClickListener(v -> { close.run(); action.run.run(); return true; });
        }
        tile.setOnClickListener(v -> {
            if (action.icon == R.drawable.ic_bluetooth) {
                boolean on = Boolean.TRUE.equals(glyph.getTag());
                boolean next = !on;
                glyph.setTag(next);
                updateRadioGlyph(tile, glyph, next);
                toggleBluetooth(ctx);
                return;
            }
            if (action.icon == R.drawable.ic_wifi) {
                boolean on = Boolean.TRUE.equals(glyph.getTag());
                boolean next = !on;
                glyph.setTag(next);
                updateRadioGlyph(tile, glyph, next);
                TextView subtitle = tile.findViewWithTag("wifi_subtitle");
                if (subtitle != null) {
                    subtitle.setText(next ? ctx.getString(R.string.connected) : ctx.getString(R.string.off));
                }
                toggleWifi(ctx);
                return;
            }
            close.run();
            action.run.run();
        });
        return tile;
    }

    private static void style(View tile, ImageView glyph, TextView label, boolean focused, Drawable surface, int icon) {
        tile.setBackground(surface);
        int ink = focused ? 0xFF191B20 : 0xFFFFFFFF;
        label.setTextColor(ink);
        TextView subtitle = tile.findViewWithTag("wifi_subtitle");
        if (subtitle != null) subtitle.setTextColor(focused ? 0xB3191B20 : 0xCCFFFFFF);
        if (icon == R.drawable.ic_wifi || icon == R.drawable.ic_bluetooth) {
            updateRadioGlyph(tile, glyph, Boolean.TRUE.equals(glyph.getTag()));
        } else {
            glyph.setImageTintList(ColorStateList.valueOf(ink));
        }
    }

    private static void updateRadioGlyph(View tile, ImageView glyph, boolean on) {
        boolean focused = tile.hasFocus();
        int color = focused ? (on ? 0xFF007AFF : 0xFF191B20) : (on ? 0xFF30B8FF : 0xFFFFFFFF);
        glyph.setImageTintList(ColorStateList.valueOf(color));
    }

    @SuppressLint("MissingPermission")
    private static boolean toggleWifi(Context ctx) {
        if (Build.VERSION.SDK_INT > 30) return false;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                boolean on = wm.isWifiEnabled();
                return wm.setWifiEnabled(!on);
            }
        } catch (SecurityException unavailable) { }
        return false;
    }

    /** Android 11 uses normal legacy Bluetooth permissions; newer systems keep the settings-page action. */
    @SuppressLint("MissingPermission")
    private static boolean toggleBluetooth(Context ctx) {
        if (Build.VERSION.SDK_INT > 30) return false;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && (adapter.isEnabled() ? adapter.disable() : adapter.enable())) return true;
        } catch (SecurityException unavailable) {
            // A vendor may still restrict the operation. Never claim a toggle succeeded when it did not.
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private static void bindBluetooth(View tile, ImageView glyph) {
        // On Android 12+ this fork does not ask for new runtime Bluetooth access just to colour an icon.
        glyph.setTag(true);
        if (Build.VERSION.SDK_INT > 30) return;
        Runnable refresh = () -> {
            boolean on = false;
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                on = adapter != null && adapter.isEnabled();
            } catch (SecurityException unavailable) { }
            glyph.setTag(on);
            updateRadioGlyph(tile, glyph, on);
        };
        refresh.run();
        tile.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private boolean registered;
            private final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) { refresh.run(); }
            };
            @Override public void onViewAttachedToWindow(View v) {
                IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
                if (Build.VERSION.SDK_INT >= 33) {
                    v.getContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else v.getContext().registerReceiver(receiver, filter);
                registered = true;
                refresh.run();
            }
            @Override public void onViewDetachedFromWindow(View v) {
                if (registered) v.getContext().unregisterReceiver(receiver);
                registered = false;
            }
        });
    }

    private static LinearLayout header(Context ctx, Runnable close, float density) {
        LinearLayout header = row(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextClock time = new TextClock(ctx);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(14);
        time.setTextColor(0xD9FFFFFF);
        time.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        time.setShadowLayer(density, 0f, density / 2f, 0x40000000);
        header.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView home = new ImageView(ctx);
        home.setId(View.generateViewId());
        home.setTag(HOME_CONTROL);
        home.setImageResource(R.drawable.ic_home_settings);
        home.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        home.setPadding(dp(8, density), dp(8, density), dp(8, density), dp(8, density));
        home.setContentDescription(ctx.getString(R.string.cc_back_home));
        home.setFocusable(true);
        home.setOnClickListener(v -> close.run());
        home.setOnFocusChangeListener((v, focused) -> {
            home.setBackground(focused ? whiteCircle(density) : null);
            home.setImageTintList(ColorStateList.valueOf(focused ? 0xFF191B20 : 0xFFFFFFFF));
        });
        header.addView(home, new LinearLayout.LayoutParams(dp(32, density), dp(32, density)));
        ImageView controls = new ImageView(ctx);
        controls.setImageResource(R.drawable.ic_control_center);
        controls.setImageTintList(ColorStateList.valueOf(0xFF191B20));
        controls.setPadding(dp(8, density), dp(8, density), dp(8, density), dp(8, density));
        controls.setBackground(whiteCircle(density));
        controls.setContentDescription(ctx.getString(R.string.settings));
        LinearLayout.LayoutParams selectedLp = new LinearLayout.LayoutParams(dp(32, density), dp(32, density));
        selectedLp.leftMargin = dp(7, density);
        header.addView(controls, selectedLp);
        FrameLayout avatar = new FrameLayout(ctx);
        avatar.setId(View.generateViewId());
        avatar.setTag(PROFILE_CONTROL);
        avatar.setFocusable(true);
        avatar.setClickable(true);
        avatar.setContentDescription(ctx.getString(R.string.profiles_switch));
        ImageView photo = new ImageView(ctx);
        photo.setBackground(ProfileAvatar.load(ctx, dp(22, density)));
        photo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        avatar.addView(photo, new FrameLayout.LayoutParams(dp(22, density), dp(22, density), Gravity.CENTER));
        Config config = Config.load(ctx);
        Motion.Focus motion = new Motion.Focus(avatar, config.focusOvershoot);
        avatar.setOnFocusChangeListener((v, focused) -> {
            avatar.setBackground(focused ? whiteCircle(density) : null);
            if (config.animations && !config.reducedMotion) motion.to(focused ? 1.08f : 1f, 0f, 0f);
        });
        avatar.setOnClickListener(v -> {
            close.run();
            try {
                ctx.startActivity(new Intent(ctx, ProfilesActivity.class));
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(32, density), dp(32, density));
        avatarLp.leftMargin = dp(5, density);
        header.addView(avatar, avatarLp);
        return header;
    }

    private static Drawable whiteCircle(float density) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFFFFFFFF);
        return circle;
    }

    /** Read only the Wi-Fi state/name exposed by existing permissions; never request location or change it. */
    private static String wifiSubtitle(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo network = cm == null ? null : cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (network == null || !network.isConnected()) return ctx.getString(R.string.not_connected);
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            String name = info == null ? null : info.getSSID();
            if (name != null && name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1);
            }
            if (name != null && !name.trim().isEmpty() && !"<unknown ssid>".equalsIgnoreCase(name)
                    && !"0x".equals(name)) return name;
        } catch (SecurityException ignored) {
            // A connected network may hide its name when location access is unavailable.
        }
        return ctx.getString(R.string.connected);
    }

    @SuppressLint("MissingPermission")
    private static void bindWifi(View tile, ImageView glyph, TextView subtitle, String label) {
        Runnable refresh = () -> {
            boolean on = false;
            try {
                WifiManager wm = (WifiManager) tile.getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                on = wm != null && wm.isWifiEnabled();
            } catch (SecurityException unavailable) { }
            glyph.setTag(on);
            updateRadioGlyph(tile, glyph, on);
            String name = on ? wifiSubtitle(tile.getContext()) : tile.getContext().getString(R.string.off);
            subtitle.setText(name);
            tile.setContentDescription(label + ", " + name);
        };
        refresh.run();
        tile.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private boolean registered;
            private final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) { refresh.run(); }
            };
            @Override public void onViewAttachedToWindow(View v) {
                IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
                filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
                if (Build.VERSION.SDK_INT >= 33) {
                    v.getContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else v.getContext().registerReceiver(receiver, filter);
                registered = true;
                refresh.run();
            }
            @Override public void onViewDetachedFromWindow(View v) {
                if (registered) v.getContext().unregisterReceiver(receiver);
                registered = false;
            }
        });
    }

    private static int dp(float value, float density) { return Math.round(value * density); }
}
