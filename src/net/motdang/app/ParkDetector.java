package net.motdang.app;

import java.util.ArrayList;

/**
 * Works out where a vehicle was left from location fixes and step events.
 * Plain Java, no android.* imports, so test/ParkDetectorTest.java runs it on the Mac.
 *
 * States: IDLE, PROBE (the phone reported motion, looking at fixes), RIDING,
 * MAYBE_PARKED (slow since a stop point, waiting for steps and a minute),
 * PARKED (a park was guessed or told; the walk-away trail records for a while).
 *
 * Inputs: onMotion, onFix, onCoarseFix, onStep, tick. Times are milliseconds
 * and do not go backwards. Output goes to the Listener.
 */
public final class ParkDetector {
    public enum State { IDLE, PROBE, RIDING, MAYBE_PARKED, PARKED }

    public interface Listener {
        void onState(State from, State to, long t);
        /** A park was guessed at the stop point (t is the time of the stop). */
        void onParked(double lat, double lng, double acc, long t);
        void onTrailPoint(double lat, double lng, long t);
        void onTrailEnd(long t);
        /** A ride started near the current park: the vehicle was taken. */
        void onLeft(long t);
    }

    /** Vehicle speed: 5 m/s is 18 km/h. */
    public static final double RIDE_SPEED = 5.0;
    /** Fixes above RIDE_SPEED needed inside RIDE_WINDOW_MS. */
    public static final int RIDE_FIXES = 3;
    public static final long RIDE_WINDOW_MS = 60_000;
    /** Without a speed field: average RIDE_SPEED over at least this span (300 m in 60 s). */
    public static final long RIDE_MIN_SPAN_MS = 30_000;
    /** Fixes less exact than this (metres) are not used. */
    public static final double MAX_ACC = 50;
    /** Below this a fix counts as stopped or walking. */
    public static final double SLOW_SPEED = 1.7;
    /** Between SLOW_SPEED and this is accepted when steps are going. */
    public static final double WALK_SPEED_MAX = 3.5;
    /** Slow this long after the stop point. */
    public static final long SLOW_MS = 60_000;
    /** This many steps, 12 in a row within STEPS_SPAN_MS, after the stop point. */
    public static final int STEPS = 12;
    public static final long STEPS_SPAN_MS = 30_000;
    /** A stop with no walking away is dropped after this long. */
    public static final long MAYBE_MAX_MS = 15 * 60_000;
    public static final long PROBE_MS = 120_000;
    /** RIDING with no usable fix this long goes back to IDLE. */
    public static final long RIDE_SILENT_MS = 10 * 60_000;
    public static final double TRAIL_STEP_M = 20;
    public static final long TRAIL_MS = 15 * 60_000;
    public static final double TRAIL_MAX_M = 1500;
    /** A ride that starts this close to the park point takes the vehicle. */
    public static final double LEFT_M = 150;
    /** Fallback without a motion sensor: two coarse fixes this far apart. */
    public static final double COARSE_MOVE_M = 300;

    private static final class Fix {
        final double lat, lng, acc, raw;
        final long t;
        double speed;   // the field when there is one, else worked out; NaN when unknown
        Fix(double lat, double lng, double acc, double raw, long t) {
            this.lat = lat; this.lng = lng; this.acc = acc; this.raw = raw; this.t = t;
        }
    }

    private final Listener out;
    private State state = State.IDLE;
    private long lastT = Long.MIN_VALUE, lastGoodT;
    private final ArrayList<Fix> win = new ArrayList<>();
    private final ArrayList<Long> steps = new ArrayList<>();

    private long probeT;
    private State probeFrom = State.IDLE;

    private Fix stop;
    private final ArrayList<Fix> cand = new ArrayList<>();

    private boolean hasCur;
    private double curLat, curLng;

    private boolean trailOn;
    private long trailT0;
    private double trailLen, trailLastLat, trailLastLng;

    private Fix coarse;

    public ParkDetector(Listener l) { out = l; }

    public State state() { return state; }
    public boolean trailActive() { return trailOn; }
    public boolean hasCurrentPark() { return hasCur; }

    // ---- inputs ----

    /** The phone says it is moving (significant motion, or a coarse jump). */
    public void onMotion(long t) {
        if (state == State.IDLE || (state == State.PARKED && !trailOn)) {
            probeFrom = state;
            probeT = t;
            enter(State.PROBE, t);
        } else if (state == State.PROBE) {
            probeT = t;
        }
    }

    /** Low-rate network fix, for phones with no motion sensor. */
    public void onCoarseFix(double lat, double lng, double acc, long t) {
        if (state != State.IDLE && !(state == State.PARKED && !trailOn)) return;
        Fix f = new Fix(lat, lng, acc, Double.NaN, t);
        if (coarse != null && t - coarse.t <= 5 * 60_000
                && dist(coarse.lat, coarse.lng, lat, lng) > Math.max(COARSE_MOVE_M, coarse.acc + acc)) {
            onMotion(t);
        }
        coarse = f;
    }

    /** speed is m/s, NaN when the fix has none. */
    public void onFix(double lat, double lng, double acc, double speed, long t) {
        if (t <= lastT) return;   // older, or the same fix again from another provider
        lastT = t;
        if (!(acc <= MAX_ACC)) { tick(t); return; }
        Fix f = new Fix(lat, lng, acc, speed, t);
        f.speed = Double.isNaN(speed) ? derive(f) : speed;
        win.add(f);
        while (!win.isEmpty() && win.get(0).t < t - 2 * RIDE_WINDOW_MS) win.remove(0);
        lastGoodT = t;

        switch (state) {
            case IDLE:
            case PROBE:
            case PARKED:
                if (vehicleMoving(t)) startRide(t);
                else if (state == State.PARKED) feedTrail(f);
                break;
            case RIDING:
                if (!Double.isNaN(f.speed) && f.speed < SLOW_SPEED) {
                    stop = f;
                    cand.clear();
                    cand.add(f);
                    enter(State.MAYBE_PARKED, t);
                }
                break;
            case MAYBE_PARKED:
                maybeFix(f);
                break;
        }
        tick(t);
    }

    public void onStep(long t) {
        steps.add(t);
        while (steps.size() > 600 || (!steps.isEmpty() && steps.get(0) < t - 30 * 60_000)) steps.remove(0);
        tick(t);
    }

    /** Timeouts and the park decision. Call now and then while GPS runs. */
    public void tick(long t) {
        switch (state) {
            case PROBE:
                if (t - probeT > PROBE_MS) enter(probeFrom == State.PARKED ? State.PARKED : State.IDLE, t);
                break;
            case RIDING:
                if (t - lastGoodT > RIDE_SILENT_MS) enter(State.IDLE, t);
                break;
            case MAYBE_PARKED:
                if (t - stop.t > MAYBE_MAX_MS) { drop(); enter(State.IDLE, t); }
                else if (t - stop.t >= SLOW_MS && walkedAway()) confirm(t);
                break;
            case PARKED:
                if (trailOn && t - trailT0 > TRAIL_MS) endTrail(t);
                break;
            default:
                break;
        }
    }

    // ---- the current park, set from outside ----

    /** A park that is already known (restored after a restart, or a previous one brought back). */
    public void setCurrentPark(double lat, double lng) {
        hasCur = true;
        curLat = lat;
        curLng = lng;
    }

    /** Resumes a walk-away trail that was running before a restart. */
    public void resumeTrail(long parkT, double lastLat, double lastLng, double length, long now) {
        if (!hasCur || now - parkT > TRAIL_MS) return;
        trailOn = true;
        trailT0 = parkT;
        trailLen = length;
        trailLastLat = lastLat;
        trailLastLng = lastLng;
        if (state == State.IDLE) enter(State.PARKED, now);
    }

    public void clearCurrentPark(long t) {
        hasCur = false;
        if (trailOn) endTrail(t);
        if (state == State.PARKED) enter(State.IDLE, t);
    }

    /** She said where she parked. The walk-away trail starts here. */
    public void parkedByUser(double lat, double lng, long t) {
        hasCur = true;
        curLat = lat;
        curLng = lng;
        drop();
        if (trailOn) endTrail(t);
        startTrail(lat, lng, t);
        enter(State.PARKED, t);
    }

    // ---- inside ----

    private void enter(State to, long t) {
        if (to == state) return;
        State from = state;
        state = to;
        out.onState(from, to, t);
    }

    private void drop() {
        stop = null;
        cand.clear();
    }

    /** Fix speed from the fix some seconds back; the part inside the GPS error does not count. */
    private double derive(Fix f) {
        for (int i = win.size() - 1; i >= 0; i--) {
            Fix p = win.get(i);
            long dt = f.t - p.t;
            if (dt < 6_000) continue;
            if (dt > 45_000) return Double.NaN;
            double d = dist(p.lat, p.lng, f.lat, f.lng) - 0.5 * (p.acc + f.acc);
            return Math.max(0, d) / (dt / 1000.0);
        }
        return Double.NaN;
    }

    private boolean vehicleMoving(long t) {
        int fast = 0, noSpeed = 0;
        ArrayList<Fix> w = new ArrayList<>();
        for (Fix f : win) {
            if (f.t < t - RIDE_WINDOW_MS) continue;
            w.add(f);
            if (Double.isNaN(f.raw)) noSpeed++;
            else if (f.raw > RIDE_SPEED) fast++;
        }
        if (fast >= RIDE_FIXES) return true;
        if (noSpeed > 0 && w.size() >= 3) {
            Fix a = w.get(0), z = w.get(w.size() - 1), m = w.get(w.size() / 2);
            long span = z.t - a.t;
            if (span >= RIDE_MIN_SPAN_MS) {
                double need = RIDE_SPEED * span / 1000.0;
                // both halves moved: one bad fix at the end does not make a ride
                return dist(a.lat, a.lng, z.lat, z.lng) > need
                        && dist(a.lat, a.lng, m.lat, m.lng) > 0.3 * need
                        && dist(m.lat, m.lng, z.lat, z.lng) > 0.3 * need;
            }
        }
        return false;
    }

    private void startRide(long t) {
        Fix start = null;
        for (Fix f : win) if (f.t >= t - RIDE_WINDOW_MS) { start = f; break; }
        if (trailOn) endTrail(t);
        boolean left = hasCur && start != null && dist(start.lat, start.lng, curLat, curLng) <= LEFT_M;
        if (left) hasCur = false;
        drop();
        enter(State.RIDING, t);
        if (left) out.onLeft(t);
    }

    private void maybeFix(Fix f) {
        double s = f.speed;
        if (Double.isNaN(s)) { cand.add(f); return; }
        if (s >= RIDE_SPEED) { cancelStop(f.t); return; }
        if (s >= SLOW_SPEED && !(s < WALK_SPEED_MAX && stepsInLast(f.t, 15_000) >= 8)) { cancelStop(f.t); return; }
        cand.add(f);
    }

    /** The vehicle moved again: a red light or a jam, not a park. */
    private void cancelStop(long t) {
        drop();
        enter(State.RIDING, t);
    }

    private int stepsInLast(long t, long ms) {
        int n = 0;
        for (long s : steps) if (s > t - ms && s <= t) n++;
        return n;
    }

    private boolean walkedAway() {
        ArrayList<Long> mine = new ArrayList<>();
        for (long s : steps) if (s >= stop.t - 3_000) mine.add(s);
        for (int i = 0; i + STEPS <= mine.size(); i++) {
            if (mine.get(i + STEPS - 1) - mine.get(i) <= STEPS_SPAN_MS) return true;
        }
        return false;
    }

    private void confirm(long t) {
        Fix s = stop;
        ArrayList<Fix> after = new ArrayList<>(cand);
        drop();
        hasCur = true;
        curLat = s.lat;
        curLng = s.lng;
        startTrail(s.lat, s.lng, s.t);
        enter(State.PARKED, t);
        out.onParked(s.lat, s.lng, s.acc, s.t);
        for (Fix f : after) if (f != s) feedTrail(f);
    }

    private void startTrail(double lat, double lng, long t0) {
        trailOn = true;
        trailT0 = t0;
        trailLen = 0;
        trailLastLat = lat;
        trailLastLng = lng;
    }

    private void feedTrail(Fix f) {
        if (!trailOn) return;
        if (f.t - trailT0 > TRAIL_MS) { endTrail(f.t); return; }
        double d = dist(trailLastLat, trailLastLng, f.lat, f.lng);
        if (d < TRAIL_STEP_M) return;
        trailLen += d;
        trailLastLat = f.lat;
        trailLastLng = f.lng;
        out.onTrailPoint(f.lat, f.lng, f.t);
        if (trailLen >= TRAIL_MAX_M) endTrail(f.t);
    }

    private void endTrail(long t) {
        if (!trailOn) return;
        trailOn = false;
        out.onTrailEnd(t);
    }

    /** Metres between two points (flat-earth, good to a few km). */
    public static double dist(double lat1, double lng1, double lat2, double lng2) {
        double dy = (lat2 - lat1) * 111_320.0;
        double dx = (lng2 - lng1) * 111_320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.sqrt(dx * dx + dy * dy);
    }
}
