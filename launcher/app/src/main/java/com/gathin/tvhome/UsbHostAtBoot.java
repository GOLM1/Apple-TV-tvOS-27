package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * usb_host_at_boot: TVs with one USB port (TCL G05) bring it up for ADB, not for drives and mice, when USB debugging
 * is on at boot; turned on after boot it leaves the port alone. So debugging goes off at shutdown and back on once
 * this helper is bound after the next boot. Only what this class turned off is turned back on, so debugging the
 * owner switched off stays off. Needs WRITE_SECURE_SETTINGS (pm grant, as build-and-install does).
 * ponytail: a sudden power cut skips the shutdown broadcast, so that boot has the port in ADB mode once.
 */
final class UsbHostAtBoot {
    private static final String TAG = "UsbHostAtBoot";
    private static final String PREFS = "usb_host_at_boot", OFF_FOR_BOOT = "adb_off_for_boot";

    private UsbHostAtBoot() {}

    /** After boot: debugging back on if shutdown turned it off. Returns the shutdown receiver to unregister. */
    static BroadcastReceiver start(Context context) {
        // Device-protected storage: the helper is bound before the user unlocks (directBootAware).
        SharedPreferences prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, 0);
        if (prefs.getBoolean(OFF_FOR_BOOT, false) && setAdb(context, 1)) prefs.edit().putBoolean(OFF_FOR_BOOT, false).apply();
        BroadcastReceiver shutdown = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) return;
                // commit, not apply: the process is about to go.
                if (!prefs.edit().putBoolean(OFF_FOR_BOOT, true).commit() || !setAdb(c, 0)) return;
                // The USB service saves the port's default (persist.sys.usb.config) a moment after the setting
                // changes; shut down before that and the next boot falls back to "adb". Shutdown waits up to 10 s
                // for this receiver, so hold it until the default no longer has adb, at most 3 s.
                long until = android.os.SystemClock.uptimeMillis() + 3000;
                while (usbDefault().contains("adb") && android.os.SystemClock.uptimeMillis() < until) {
                    android.os.SystemClock.sleep(100);
                }
                Log.i(TAG, "usb default at shutdown: " + usbDefault());
            }
        };
        context.registerReceiver(shutdown, new IntentFilter(Intent.ACTION_SHUTDOWN));
        return shutdown;
    }

    /** persist.sys.usb.config, the functions the port boots with; "" when it cannot be read. */
    private static String usbDefault() {
        try {
            return String.valueOf(Class.forName("android.os.SystemProperties").getMethod("get", String.class)
                    .invoke(null, "persist.sys.usb.config"));
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    private static boolean setAdb(Context context, int value) {
        try {
            return Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, value);
        } catch (SecurityException e) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS: " + e.getMessage());
            return false;
        }
    }
}
