package net.motdang.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** The parking notifications. */
final class ParkNotes {
    static final String PAGE = "https://motdang.net/appmap/";
    static final String CH_ONGOING = "park", CH_GUESS = "park_guess", CH_REMIND = "park_remind";
    static final int ONGOING = 20, GUESS = 21, REMIND = 22;

    private ParkNotes() {}

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_ONGOING, "ที่จอด · Parking", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel g = new NotificationChannel(CH_GUESS, "จอดตรงนี้ · Parked here", NotificationManager.IMPORTANCE_DEFAULT);
        g.enableVibration(true);
        g.setSound(null, null);
        nm.createNotificationChannel(g);
        NotificationChannel r = new NotificationChannel(CH_REMIND, "เตือน · Reminders", NotificationManager.IMPORTANCE_HIGH);
        r.enableVibration(true);
        nm.createNotificationChannel(r);
    }

    /** Opens the parking page at a fragment: mine, back, photo. */
    static PendingIntent page(Context c, int req, String fragment) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(PAGE + (fragment == null ? "" : "#" + fragment)), c, MainActivity.class);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The service's own notification. */
    static Notification ongoing(Context c, Park p, long now) {
        channels(c);
        Notification.Builder b = new Notification.Builder(c, CH_ONGOING)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (p == null) {
            b.setContentTitle(ParkText.IDLE_TITLE).setContentIntent(page(c, 30, null));
        } else {
            b.setContentTitle(ParkText.ongoingTitle(p.vehicle, ParkStore.clock(p.t), now - p.t))
                    .setContentIntent(page(c, 31, "mine"))
                    .addAction(new Notification.Action.Builder(null, "พาไป · Walk me back", page(c, 32, "back")).build());
        }
        return b.build();
    }

    static void guess(Context c, Park p) {
        channels(c);
        String clock = ParkStore.clock(p.t);
        Intent no = new Intent(c, ParkReceiver.class).setAction(ParkReceiver.ACT_NOT_MINE).putExtra("t", p.t);
        PendingIntent notMine = PendingIntent.getBroadcast(c, 33, no, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH_GUESS)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(ParkText.guessTitle(clock))
                .setContentText(ParkText.GUESS_TEXT)
                .setAutoCancel(true)
                .setContentIntent(page(c, 34, "mine"))
                .addAction(new Notification.Action.Builder(null, "ไม่ใช่ · Not mine", notMine).build())
                .addAction(new Notification.Action.Builder(null, "ถ่ายรูป · Photo", page(c, 35, "photo")).build())
                .build();
        c.getSystemService(NotificationManager.class).notify(GUESS, n);
    }

    static void cancelGuess(Context c) { c.getSystemService(NotificationManager.class).cancel(GUESS); }

    static void remind(Context c, String text) {
        channels(c);
        Notification n = new Notification.Builder(c, CH_REMIND)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("มดแดง · Mot Dang")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(page(c, 36, "back"))
                .build();
        c.getSystemService(NotificationManager.class).notify(REMIND, n);
    }

    static void cancelRemind(Context c) { c.getSystemService(NotificationManager.class).cancel(REMIND); }
}
