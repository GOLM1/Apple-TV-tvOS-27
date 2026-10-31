package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService):
 * a few glass rows under the Settings pill, over Home or whatever app is playing. Translucent and in a
 * task of its own, so the app behind keeps running and Back returns to it. Pressing Settings again
 * closes the panel.
 */
public final class QuickSettingsActivity extends Activity {
    private boolean homeHeaderHidden;
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        if (Config.load(this).topMenu) {
            showControlCenter();
            return;
        }
        LinearLayout rows = findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_settings, R.string.tv_settings, 0, SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        addRow(rows, R.drawable.ic_picture, R.string.quick_picture, 0, SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS));
        addRow(rows, R.drawable.ic_wifi, R.string.quick_network, 0, SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, R.drawable.ic_apps, R.string.quick_apps, R.string.quick_apps_hint,
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addRow(rows, R.drawable.ic_home_settings, R.string.launcher_settings, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        rows.getChildAt(0).requestFocus();
    }

    /** Reuse the profile menu controls over the current app; Back leaves that app in place. */
    private void showControlCenter() {
        float density = getResources().getDisplayMetrics().density;
        List<ControlCenter.Action> actions = new ArrayList<>();
        actions.add(new ControlCenter.Action(R.drawable.ic_wifi, getString(R.string.cc_wifi),
                () -> launch(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS))));
        actions.add(new ControlCenter.Action(R.drawable.ic_bluetooth, getString(R.string.cc_bluetooth),
                () -> launch(SystemSettings.intent(this, Settings.ACTION_BLUETOOTH_SETTINGS))));
        actions.add(new ControlCenter.Action(R.drawable.ic_input, getString(R.string.inputs),
                () -> launch(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_VIEW_INPUTS))));
        actions.add(new ControlCenter.Action(R.drawable.ic_power, getString(R.string.cc_sleep), () -> {
            if (!HomeKeyService.sleep()) launch(SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        }));
        actions.add(new ControlCenter.Action(R.drawable.ic_audio, getString(R.string.cc_audio),
                () -> launch(SystemSettings.intent(this, Settings.ACTION_SOUND_SETTINGS))));
        actions.add(new ControlCenter.Action(R.drawable.ic_picture, getString(R.string.cc_display),
                () -> launch(SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS))));
        actions.add(new ControlCenter.Action(R.drawable.ic_apps, getString(R.string.cc_apps),
                () -> launch(SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))));
        actions.add(new ControlCenter.Action(R.drawable.ic_home_settings, getString(R.string.launcher_settings),
                () -> launch(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS))));
        actions.add(new ControlCenter.Action(R.drawable.ic_settings, getString(R.string.settings),
                () -> launch(SystemSettings.intent(this, Settings.ACTION_SETTINGS))));
        LinearLayout panel = ControlCenter.build(this, actions, this::finish, density);
        // Do not sample Home's cached backdrop over a different app: Android 11 cannot blur other apps.
        panel.setBackground(null);
        panel.setOnClickListener(v -> {});
        FrameLayout scrim = findViewById(R.id.scrim);
        scrim.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ControlCenter.widthPx(density),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.topMargin = Math.round(36 * density);
        lp.setMarginEnd(Math.round(56 * density));
        scrim.addView(panel, lp);
        homeHeaderHidden = getIntent().getBooleanExtra(ControlCenter.HOME_BACKDROP, false);
        if (homeHeaderHidden) ControlCenter.setExpanded(true);
        ControlCenter.focusFirst(panel);
        Config config = Config.load(this);
        panel.post(() -> Motion.reveal(panel, config.animations, config.reducedMotion));
    }

    @Override
    protected void onDestroy() {
        if (homeHeaderHidden) ControlCenter.setExpanded(false);
        super.onDestroy();
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    private void addRow(LinearLayout rows, int icon, int title, int status, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != 0) hint.setText(status);
        else hint.setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        rows.addView(row);
    }
}
