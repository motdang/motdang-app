package net.motdang.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot and update (start watching again), the reminder alarm, and the Not mine button. */
public class ParkReceiver extends BroadcastReceiver {
    static final String ACT_REMIND = "net.motdang.app.PARK_REMIND";
    static final String ACT_NOT_MINE = "net.motdang.app.PARK_NOT_MINE";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (a == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            ParkService.ensure(c);
            reschedule(c);
        } else if (ACT_REMIND.equals(a)) {
            String text = ParkStore.prefs(c).getString("remind_text", "");
            ParkStore.prefs(c).edit().remove("remind_at").remove("remind_text").apply();
            if (!text.isEmpty()) ParkNotes.remind(c, text);
        } else if (ACT_NOT_MINE.equals(a)) {
            if (ParkStore.notMine(c, i.getLongExtra("t", 0))) {
                ParkNotes.cancelGuess(c);
                ParkService.syncIfRunning();
            }
        }
    }

    private static PendingIntent alarmIntent(Context c) {
        return PendingIntent.getBroadcast(c, 40, new Intent(c, ParkReceiver.class).setAction(ACT_REMIND),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** One reminder at a time. An inexact alarm: it may run a few minutes late. */
    static void schedule(Context c, long atMs, String text) {
        long at = Math.max(atMs, System.currentTimeMillis() + 5_000);
        ParkStore.prefs(c).edit().putLong("remind_at", at).putString("remind_text", text).apply();
        c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c));
    }

    static void clear(Context c) {
        c.getSystemService(AlarmManager.class).cancel(alarmIntent(c));
        ParkStore.prefs(c).edit().remove("remind_at").remove("remind_text").apply();
        ParkNotes.cancelRemind(c);
    }

    /** After a restart the alarm is gone; set it again, or show it now if it is under an hour late. */
    static void reschedule(Context c) {
        long at = ParkStore.prefs(c).getLong("remind_at", 0);
        String text = ParkStore.prefs(c).getString("remind_text", "");
        if (at == 0 || text.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (at < now - 3_600_000L) {
            ParkStore.prefs(c).edit().remove("remind_at").remove("remind_text").apply();
        } else {
            schedule(c, at, text);
        }
    }
}
