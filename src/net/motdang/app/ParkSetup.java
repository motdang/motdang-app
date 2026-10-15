package net.motdang.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * The permission steps for parking detection, one per call, in this order:
 * location, background, activity, notifications, battery, vivo.
 * next() names the step that is due; run() starts it.
 */
final class ParkSetup {
    static final int REQ = 20;

    private ParkSetup() {}

    private static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean location(Context c) { return has(c, Manifest.permission.ACCESS_FINE_LOCATION); }
    static boolean background(Context c) { return Build.VERSION.SDK_INT < 29 || has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION); }
    static boolean activity(Context c) { return Build.VERSION.SDK_INT < 29 || has(c, Manifest.permission.ACTIVITY_RECOGNITION); }
    static boolean notifications(Context c) { return Build.VERSION.SDK_INT < 33 || has(c, Manifest.permission.POST_NOTIFICATIONS); }
    static boolean battery(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static boolean isVivo() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        return m.contains("vivo") || m.contains("iqoo");
    }

    /** on, off, or the first thing the phone still needs. */
    static String state(Context c) {
        if (!ParkStore.watching(c)) return "off";
        if (!location(c)) return "needs-location";
        if (!background(c)) return "needs-background";
        if (!activity(c)) return "needs-activity";
        if (!battery(c)) return "needs-battery";
        return "on";
    }

    static String next(Context c) {
        if (!location(c)) return "location";
        if (!background(c)) return "background";
        if (!activity(c)) return "activity";
        if (!notifications(c)) return "notifications";
        if (!battery(c)) return "battery";
        if (isVivo() && !prefs(c).getBoolean("vivo_asked", false)) return "vivo";
        return "done";
    }

    static String run(Activity a) {
        String step = next(a);
        switch (step) {
            case "location":
                ask(a, "loc", Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
                break;
            case "background":
                ask(a, "bg", Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                break;
            case "activity":
                ask(a, "act", Manifest.permission.ACTIVITY_RECOGNITION);
                break;
            case "notifications":
                ask(a, "notif", Manifest.permission.POST_NOTIFICATIONS);
                break;
            case "battery":
                battery(a, prefs(a).getInt("battery_asked", 0) > 0);
                prefs(a).edit().putInt("battery_asked", prefs(a).getInt("battery_asked", 0) + 1).apply();
                break;
            case "vivo":
                prefs(a).edit().putBoolean("vivo_asked", true).apply();
                vivo(a);
                break;
            default:
                break;
        }
        return step;
    }

    private static SharedPreferences prefs(Context c) { return ParkStore.prefs(c); }

    /** Asks for a permission. After two asks the system stops showing its box, so the app's own settings page opens. */
    private static void ask(Activity a, String key, String... perms) {
        int n = prefs(a).getInt("asked_" + key, 0);
        if (n > 0 && !a.shouldShowRequestPermissionRationale(perms[0])) {
            appSettings(a);
        } else {
            prefs(a).edit().putInt("asked_" + key, n + 1).apply();
            a.requestPermissions(perms, REQ);
        }
    }

    static void appSettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            // no settings screen to show
        }
    }

    private static void battery(Context c, boolean again) {
        try {
            Intent i = again
                    ? new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                c.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                appSettings(c);
            }
        }
    }

    /** vivo / iQOO (Funtouch, OriginOS) screens for auto-start and background running, tried in turn. */
    private static final String[][] VIVO = {
        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"},
        {"com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"},
        {"com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"},
        {"com.iqoo.secure", "com.iqoo.secure.MainActivity"},
    };

    static boolean vivo(Context c) {
        for (String[] v : VIVO) {
            try {
                c.startActivity(new Intent().setComponent(new ComponentName(v[0], v[1])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e) {
                // not on this phone, or not open to apps: try the next
            }
        }
        appSettings(c);
        return false;
    }
}
