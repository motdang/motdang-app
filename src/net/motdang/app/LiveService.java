package net.motdang.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * A live share that keeps going with the screen locked: a foreground service
 * with its notification, sending the phone's position to motdang.net/api/live
 * (mot-dang/publish/live.js) until the hour is up or someone taps Stop.
 *
 * Same rhythm as the web page: on 25 m of movement or 15 s, and a 30 s beat so
 * the person watching can tell a phone standing still from one that stopped.
 * Location comes from the phone's own LocationManager — GPS and network.
 */
public class LiveService extends Service implements LocationListener {
    static final String STOP = "net.motdang.app.STOP";
    static final Pattern ID = Pattern.compile("[a-z2-7]{16}");
    private static final String API = "https://motdang.net/api/live/";
    private static final String CH = "live";
    private static final int NOTE = 1;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private LocationManager lm;
    private String id, tok;
    private long until, sentAt, okAt;
    private Location last, sentLoc;
    private boolean running;

    private final Runnable beat = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (System.currentTimeMillis() >= until) { end(false); return; }
            push(true);
            h.postDelayed(this, 30_000);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        SharedPreferences p = prefs();
        if (i != null && STOP.equals(i.getAction())) {
            if (id == null) { id = p.getString("id", null); tok = p.getString("tok", null); }
            end(true);
            return START_NOT_STICKY;
        }
        if (i != null && i.hasExtra("id")) {
            if (running && id != null && !id.equals(i.getStringExtra("id"))) tell();  // a new share replaces the old one
            id = i.getStringExtra("id");
            tok = i.getStringExtra("tok");
            until = i.getLongExtra("until", 0);
            okAt = 0;
            p.edit().putString("id", id).putString("tok", tok).putLong("until", until).putLong("ok", 0).apply();
        } else {                       // restarted by the system: carry on from what was saved
            id = p.getString("id", null);
            tok = p.getString("tok", null);
            until = p.getLong("until", 0);
        }
        if (id == null || tok == null || !ID.matcher(id).matches() || until <= System.currentTimeMillis()) {
            end(false);
            return START_NOT_STICKY;
        }
        channel();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTE, note());
        if (!running) {
            running = true;
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            listen(LocationManager.GPS_PROVIDER);
            listen(LocationManager.NETWORK_PROVIDER);
            h.postDelayed(beat, 30_000);
        }
        return START_REDELIVER_INTENT;
    }

    private void listen(String provider) {
        try {
            if (lm.isProviderEnabled(provider)) {
                lm.requestLocationUpdates(provider, 5_000, 0, this, Looper.getMainLooper());
                Location k = lm.getLastKnownLocation(provider);
                if (k != null && System.currentTimeMillis() - k.getTime() < 120_000) onLocationChanged(k);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // no permission or no such provider: the other one may still answer
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        // A fix is kept if it is newer by 10 s, or about as good as the one held.
        if (last == null || l.getTime() - last.getTime() > 10_000 || l.getAccuracy() <= last.getAccuracy() * 1.5f) {
            last = l;
            push(false);
        }
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}

    private void push(boolean force) {
        if (last == null || !running) return;
        long t = System.currentTimeMillis();
        if (t >= until) { end(false); return; }
        if (t - sentAt < 4_000) return;
        if (!force && t - sentAt < 15_000 && sentLoc != null && sentLoc.distanceTo(last) < 25) return;
        sentAt = t;
        sentLoc = new Location(last);
        final String body = String.format(Locale.ROOT, "{\"tok\":\"%s\",\"lat\":%.6f,\"lng\":%.6f,\"acc\":%.0f}",
                tok, last.getLatitude(), last.getLongitude(), last.hasAccuracy() ? last.getAccuracy() : 0f);
        final String sid = id;
        net.execute(() -> {
            int code = post(API + sid, body);
            h.post(() -> {
                if (!sid.equals(id)) return;
                if (code == 410 || code == 403 || code == 404) end(false);
                else if (code == 200) {
                    okAt = System.currentTimeMillis();
                    prefs().edit().putLong("ok", okAt).apply();
                    getSystemService(NotificationManager.class).notify(NOTE, note());
                }
            });
        });
    }

    static int post(String url, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("user-agent", "MotDangApp/0.2 (Android)");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            return c.getResponseCode();
        } catch (Exception e) {
            return -1;                 // no signal: the next fix or beat tries again
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Ends the share here; tellServer also ends it on motdang.net at once. */
    private void end(boolean tellServer) {
        if (tellServer) tell();
        running = false;
        h.removeCallbacks(beat);
        if (lm != null) try { lm.removeUpdates(this); } catch (Exception e) { /* already gone */ }
        prefs().edit().clear().apply();
        id = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void tell() {
        if (id == null || tok == null) return;
        final String url = API + id, body = "{\"tok\":\"" + tok + "\",\"stop\":1}";
        net.execute(() -> post(url, body));
    }

    private SharedPreferences prefs() { return getSharedPreferences("live", Context.MODE_PRIVATE); }

    private void channel() {
        NotificationChannel c = new NotificationChannel(CH, "ตำแหน่งสด · Live location", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }

    private Notification note() {
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        String text = "ถึง " + hm.format(new Date(until)) + " · until " + hm.format(new Date(until))
                + (okAt > 0 ? " — ส่งล่าสุด " + hm.format(new Date(okAt)) + " · last sent " + hm.format(new Date(okAt)) : "");
        PendingIntent show = PendingIntent.getActivity(this, 0,
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://motdang.net/live.html?l=" + id), this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, LiveService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("กำลังส่งตำแหน่งสด · Sharing live location")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(show)
                .addAction(new Notification.Action.Builder(null, "หยุดส่ง · Stop", stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacks(beat);
        if (lm != null) try { lm.removeUpdates(this); } catch (Exception e) { /* already gone */ }
        net.shutdown();
        super.onDestroy();
    }
}
