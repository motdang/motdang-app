package net.motdang.app;

import android.Manifest;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Notices where the vehicle was left. Foreground service of type location.
 *
 * Idle: no GPS. The phone's significant-motion sensor is armed and the step
 * detector listens. On motion the service looks at GPS for up to two minutes
 * (PROBE); a ride keeps GPS at 4 s, a park keeps it at 5 s while the walk-away
 * trail records, then GPS goes off and the motion sensor is armed again.
 *
 * A phone with no significant-motion sensor listens to the passive provider
 * and asks the network provider every two minutes instead.
 *
 * The decisions are ParkDetector's. This class feeds it and keeps the store.
 */
public class ParkService extends Service implements ParkDetector.Listener {
    private static volatile ParkService instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ParkDetector det = new ParkDetector(this);
    private LocationManager lm;
    private SensorManager sm;
    private Sensor sig, stepSensor;
    private boolean started, sigArmed, gpsOn, coarseOn, tickPending;
    private long gpsEvery, cooldownUntil, knownT;

    // ---- starting and stopping, from outside ----

    /** Starts the service when watching is on and the phone allows it. Call from the foreground, boot or an update. */
    static void ensure(Context c) {
        if (!ParkStore.watching(c)) return;
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            c.startForegroundService(new Intent(c, ParkService.class));
        } catch (Exception e) {
            // the system will not start it from here; the app tries again when opened
        }
    }

    static void stop() {
        final ParkService s = instance;
        if (s != null) s.h.post(s::shutdown);
    }

    /** The store changed from outside (the page told, noted, cleared or dropped a park). */
    static void syncIfRunning() {
        final ParkService s = instance;
        if (s != null) s.h.post(s::sync);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sig = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
        stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, f);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        try {
            Park cur = ParkStore.current(this);
            long now = System.currentTimeMillis();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ParkNotes.ONGOING, ParkNotes.ongoing(this, cur, now), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(ParkNotes.ONGOING, ParkNotes.ongoing(this, cur, now));
            }
        } catch (RuntimeException e) {
            // not allowed to run as a location service right now
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ParkStore.watching(this)
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            sync();
            if (stepSensor != null) sm.registerListener(stepListener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);
            if (sig == null) startFallback();
            applyMode();
            h.postDelayed(minute, 60_000);
        }
        return START_STICKY;
    }

    private void shutdown() {
        started = false;
        h.removeCallbacksAndMessages(null);
        try { if (stepSensor != null) sm.unregisterListener(stepListener); } catch (Exception e) { /* gone */ }
        disarm();
        try { lm.removeUpdates(gpsListener); lm.removeUpdates(fallbackListener); } catch (Exception e) { /* gone */ }
        gpsOn = false;
        coarseOn = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        started = false;
        h.removeCallbacksAndMessages(null);
        try { sm.unregisterListener(stepListener); } catch (Exception e) { /* gone */ }
        disarm();
        try { lm.removeUpdates(gpsListener); lm.removeUpdates(fallbackListener); } catch (Exception e) { /* gone */ }
        try { unregisterReceiver(screen); } catch (Exception e) { /* gone */ }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ---- the detector's view of the store ----

    /** Lines the detector up with the store: the current park, and a trail still recording. */
    private void sync() {
        Park cur = ParkStore.current(this);
        long now = System.currentTimeMillis();
        long t = cur == null ? 0 : cur.t;
        if (t != knownT) {
            knownT = t;
            det.clearCurrentPark(now);          // also ends a trail that belonged to the old park
            if (cur == null) {
                // nothing current
            } else if (Park.TOLD.equals(cur.how) && now - cur.t < 60_000) {
                det.parkedByUser(cur.lat, cur.lng, cur.t);
            } else {
                det.setCurrentPark(cur.lat, cur.lng);
                if (now - cur.t < ParkDetector.TRAIL_MS && !det.trailActive() && cur.trail.size() > 0) {
                    double[] last = cur.trail.get(cur.trail.size() - 1);
                    double len = 0;
                    for (int k = 1; k < cur.trail.size(); k++)
                        len += ParkDetector.dist(cur.trail.get(k - 1)[0], cur.trail.get(k - 1)[1], cur.trail.get(k)[0], cur.trail.get(k)[1]);
                    det.resumeTrail(cur.t, last[0], last[1], len, now);
                }
            }
        } else if (cur != null) {
            det.setCurrentPark(cur.lat, cur.lng);
        }
        applyMode();
        refreshOngoing();
    }

    private void refreshOngoing() {
        getSystemService(android.app.NotificationManager.class).notify(ParkNotes.ONGOING,
                ParkNotes.ongoing(this, ParkStore.current(this), System.currentTimeMillis()));
    }

    // ---- detector output ----

    @Override public void onState(ParkDetector.State from, ParkDetector.State to, long t) {
        if (from == ParkDetector.State.PROBE && to != ParkDetector.State.RIDING) {
            cooldownUntil = SystemClock.uptimeMillis() + 3 * 60_000;   // a walk should not keep the GPS on all day
        }
    }

    @Override public void onParked(double lat, double lng, double acc, long t) {
        Park p = ParkStore.guess(this, lat, lng, acc, t);
        knownT = t;
        ParkNotes.guess(this, p);
        refreshOngoing();
    }

    @Override public void onTrailPoint(double lat, double lng, long t) {
        ParkStore.trail(this, knownT, lat, lng, t);
    }

    @Override public void onTrailEnd(long t) {}

    @Override public void onLeft(long t) {
        ParkStore.leave(this, t);
        knownT = 0;
        ParkNotes.cancelGuess(this);
        refreshOngoing();
    }

    // ---- what is switched on ----

    private boolean wantGps() {
        ParkDetector.State s = det.state();
        return s == ParkDetector.State.PROBE || s == ParkDetector.State.RIDING
                || s == ParkDetector.State.MAYBE_PARKED || (s == ParkDetector.State.PARKED && det.trailActive());
    }

    private void applyMode() {
        if (!started) return;
        if (wantGps()) {
            disarm();
            long every = det.state() == ParkDetector.State.RIDING || det.state() == ParkDetector.State.MAYBE_PARKED ? 4_000 : 5_000;
            startGps(every);
            if (!tickPending) { tickPending = true; h.postDelayed(tick, 10_000); }
        } else {
            stopGps();
            h.removeCallbacks(tick);
            tickPending = false;
            if (sig != null) {
                long wait = cooldownUntil - SystemClock.uptimeMillis();
                h.removeCallbacks(rearm);
                if (wait <= 0) arm(); else h.postDelayed(rearm, wait);
            }
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tickPending = false;
            det.tick(System.currentTimeMillis());
            applyMode();
        }
    };

    private final Runnable rearm = new Runnable() {
        @Override public void run() { applyMode(); }
    };

    private final Runnable minute = new Runnable() {
        @Override public void run() {
            if (!started) return;
            refreshOngoing();
            h.postDelayed(this, 60_000);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (started) refreshOngoing(); }
    };

    private void arm() {
        if (sig == null || sigArmed) return;
        sigArmed = sm.requestTriggerSensor(motion, sig);
    }

    private void disarm() {
        if (sig != null && sigArmed) {
            try { sm.cancelTriggerSensor(motion, sig); } catch (Exception e) { /* gone */ }
        }
        sigArmed = false;
    }

    private final TriggerEventListener motion = new TriggerEventListener() {
        @Override public void onTrigger(TriggerEvent e) {
            sigArmed = false;             // one shot; applyMode arms it again
            h.post(() -> {
                if (!started) return;
                det.onMotion(System.currentTimeMillis());
                applyMode();
            });
        }
    };

    private final SensorEventListener stepListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            long now = System.currentTimeMillis();
            long t = now - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000L;
            if (t > now || t < now - 600_000) t = now;
            det.onStep(t);
            applyMode();
        }
        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };

    // ---- location ----

    private void startGps(long every) {
        if (gpsOn && gpsEvery == every) return;
        try {
            lm.removeUpdates(gpsListener);
            boolean any = false;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, every, 0, gpsListener, Looper.getMainLooper());
                any = true;
            }
            gpsOn = any;
            gpsEvery = every;
        } catch (SecurityException | IllegalArgumentException e) {
            gpsOn = false;
        }
    }

    private void stopGps() {
        if (!gpsOn) return;
        try { lm.removeUpdates(gpsListener); } catch (Exception e) { /* gone */ }
        gpsOn = false;
    }

    /** No motion sensor: the passive provider hears other apps' fixes, the network provider is asked every 2 min. */
    private void startFallback() {
        if (coarseOn) return;
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, fallbackListener, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 120_000, 0, fallbackListener, Looper.getMainLooper());
            }
            coarseOn = true;
        } catch (SecurityException | IllegalArgumentException e) {
            // the phone has neither; nothing to listen to
        }
    }

    private final LocationListener gpsListener = new Feed();
    private final LocationListener fallbackListener = new Feed();

    private final class Feed implements LocationListener {
        @Override public void onLocationChanged(Location l) {
            if (!started) return;
            long now = System.currentTimeMillis();
            long t = now - (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
            if (t > now) t = now;
            String prov = l.getProvider();
            double acc = l.hasAccuracy() ? l.getAccuracy() : 999;
            if (LocationManager.NETWORK_PROVIDER.equals(prov) || "fused".equals(prov)) {
                det.onCoarseFix(l.getLatitude(), l.getLongitude(), acc, t);
            } else {
                det.onFix(l.getLatitude(), l.getLongitude(), acc, l.hasSpeed() ? l.getSpeed() : Double.NaN, t);
            }
            applyMode();
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    }
}
