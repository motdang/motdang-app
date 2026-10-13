import net.motdang.app.ParkDetector;
import net.motdang.app.ParkDetector.State;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** java -cp build/test ParkDetectorTest  (build.sh runs it). Synthetic traces, 5 s fixes. */
public class ParkDetectorTest {
    static int failed = 0;

    static void check(boolean ok, String what) {
        if (ok) System.out.println("  ok   " + what);
        else { failed++; System.out.println("  FAIL " + what); }
    }

    /** A phone carried through a scene. Moves north unless told otherwise. */
    static class Sim implements ParkDetector.Listener {
        final ParkDetector d = new ParkDetector(this);
        final Random rnd = new Random(7);
        long t = 1_800_000_000_000L;
        double lat = 18.7900, lng = 98.9800, heading = 0;
        boolean speedField = true;
        double jitterM = 2, acc = 8;
        double stepFrac = 0;
        double lastFixLat, lastFixLng; long lastFixT;

        final List<double[]> parked = new ArrayList<>();   // lat, lng, acc, t, callback time
        final List<double[]> trail = new ArrayList<>();
        int left, trailEnds;
        final List<State> states = new ArrayList<>();

        public void onState(State f, State to, long at) { states.add(to); }
        public void onParked(double a, double b, double c, long at) { parked.add(new double[]{a, b, c, at, t}); }
        public void onTrailPoint(double a, double b, long at) { trail.add(new double[]{a, b, at}); }
        public void onTrailEnd(long at) { trailEnds++; }
        public void onLeft(long at) { left++; }

        /** Moves for secs at mps with stepsPerSec steps a second; a fix every 5 s. */
        void go(int secs, double mps, double stepsPerSec) {
            for (int s = 1; s <= secs; s++) {
                t += 1000;
                double m = mps;
                lat += m * Math.cos(Math.toRadians(heading)) / 111_320.0;
                lng += m * Math.sin(Math.toRadians(heading)) / (111_320.0 * Math.cos(Math.toRadians(lat)));
                stepFrac += stepsPerSec;
                while (stepFrac >= 1) { stepFrac -= 1; d.onStep(t); }
                if (s % 5 == 0) fix(mps);
            }
        }
        void ride(int secs, double mps) { go(secs, mps, 0); }
        void walk(int secs, double mps) { go(secs, mps, 1.8); }
        void stand(int secs) { go(secs, 0, 0); }
        void fix(double mps) {
            double jy = (rnd.nextDouble() - 0.5) * 2 * jitterM, jx = (rnd.nextDouble() - 0.5) * 2 * jitterM;
            double speed = Math.max(0, mps + (rnd.nextDouble() - 0.5) * 0.3);
            d.onFix(lat + jy / 111_320.0, lng + jx / (111_320.0 * Math.cos(Math.toRadians(lat))), acc,
                    speedField ? speed : Double.NaN, t);
            lastFixLat = lat; lastFixLng = lng; lastFixT = t;
        }
        /** Slows 8 -> 0 over 6 fixes. Returns where the first slow fix (< 1.7 m/s) falls. */
        double[] brake() {
            double[] sp = {6, 4, 2.5, 1.2, 0.6, 0.0};
            double[] first = null;
            for (double s : sp) {
                go(5, s, 0);
                if (first == null && s < 1.7) first = new double[]{lat, lng, t};
            }
            return first;
        }
        void tickTo(long end) { while (t < end) { t += 5000; d.tick(t); } }
        double metersFrom(double[] p) { return ParkDetector.dist(lat, lng, p[0], p[1]); }
    }

    public static void main(String[] a) {
        rideThenWalk();
        rideThenWalkNoSpeed();
        redLight();
        redLightNoSpeed();
        trafficJam();
        cafeJitter();
        cafeJitterNoSpeed();
        walkOnly();
        rideParkRideSameSpot();
        rideParkRideFarAway();
        probeExpires();
        poorFixesIgnored();
        stepsNeeded();
        trailLimits();
        walkWhileStopped();
        coarseFallback();
        System.out.println(failed == 0 ? "\nall passed" : "\n" + failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void rideThenWalk() {
        System.out.println("ride, stop, walk away: parks at the stop point");
        Sim s = new Sim();
        s.d.onMotion(s.t);
        check(s.d.state() == State.PROBE, "motion starts a probe");
        s.walk(20, 1.3);
        s.ride(60, 9);
        check(s.d.state() == State.RIDING, "riding after a minute at 9 m/s");
        double[] stop = s.brake();
        s.stand(15);
        check(s.parked.isEmpty(), "not parked before the walk");
        s.walk(100, 1.4);
        check(s.parked.size() == 1, "one park guessed");
        if (s.parked.size() == 1) {
            double[] p = s.parked.get(0);
            check(ParkDetector.dist(p[0], p[1], stop[0], stop[1]) < 12, "park point is the stop point, not where she is");
            check(s.metersFrom(new double[]{p[0], p[1]}) > 80, "she is more than 80 m from it by then");
            check((long) p[3] == (long) stop[2], "park time is the stop time");
            check(p[4] - p[3] >= 60_000, "decided at least 60 s after the stop");
        }
        check(s.d.state() == State.PARKED, "state PARKED");
        check(s.trail.size() >= 3, "trail has points (" + s.trail.size() + ")");
        boolean spaced = true;
        for (int i = 1; i < s.trail.size(); i++)
            if (ParkDetector.dist(s.trail.get(i - 1)[0], s.trail.get(i - 1)[1], s.trail.get(i)[0], s.trail.get(i)[1]) < 15) spaced = false;
        check(spaced, "trail points about 20 m apart");
        s.walk(14 * 60, 1.4);
        check(s.trailEnds == 1 && !s.d.trailActive(), "trail ends at 15 minutes");
        check(s.d.state() == State.PARKED, "still PARKED after the trail");
    }

    static void rideThenWalkNoSpeed() {
        System.out.println("same, GPS without a speed field");
        Sim s = new Sim();
        s.speedField = false;
        s.d.onMotion(s.t);
        s.ride(90, 8);
        check(s.d.state() == State.RIDING, "riding from distance alone");
        double[] stop = s.brake();
        s.stand(10);
        s.walk(110, 1.4);
        check(s.parked.size() == 1, "one park guessed");
        if (s.parked.size() == 1) {
            double[] p = s.parked.get(0);
            double e = ParkDetector.dist(p[0], p[1], stop[0], stop[1]);
            check(e < 45, "park point within 45 m of the stop (" + Math.round(e) + " m)");
        }
    }

    static void redLight() {
        System.out.println("red light: no park");
        Sim s = new Sim();
        s.d.onMotion(s.t);
        s.ride(60, 9);
        s.brake();
        s.stand(45);
        s.ride(60, 9);
        check(s.parked.isEmpty(), "no park");
        check(s.d.state() == State.RIDING, "still riding");
        // a few vibration steps at the light
        Sim v = new Sim();
        v.d.onMotion(v.t);
        v.ride(60, 9);
        v.brake();
        v.go(45, 0, 0.1);
        v.ride(60, 9);
        check(v.parked.isEmpty(), "no park with a few vibration steps");
    }

    static void redLightNoSpeed() {
        System.out.println("red light, no speed field: no park");
        Sim s = new Sim();
        s.speedField = false;
        s.d.onMotion(s.t);
        s.ride(90, 9);
        s.brake();
        s.stand(45);
        s.ride(60, 9);
        check(s.parked.isEmpty(), "no park");
    }

    static void trafficJam() {
        System.out.println("traffic jam, 3 minutes creeping: no park");
        Sim s = new Sim();
        s.d.onMotion(s.t);
        s.ride(60, 9);
        s.brake();
        for (int i = 0; i < 6; i++) { s.go(10, 0.3, 0); s.go(10, 2.6, 0); s.go(10, 0, 0.15); }
        s.ride(60, 9);
        check(s.parked.isEmpty(), "no park");
        check(s.d.state() == State.RIDING, "still riding");
        Sim q = new Sim();
        q.d.onMotion(q.t);
        q.ride(60, 9);
        q.brake();
        q.go(180, 0, 0.12);   // 20-odd stray steps over 3 minutes, never 12 close together
        q.stand(10);
        check(q.parked.isEmpty(), "stray vibration steps over 3 min do not park");
    }

    static void cafeJitter() {
        System.out.println("cafe: GPS jitter, no ride");
        Sim s = new Sim();
        s.jitterM = 12; s.acc = 20;
        s.d.onMotion(s.t);
        for (int i = 0; i < 40; i++) { s.go(5, 0, i % 7 == 0 ? 1 : 0); }
        check(!s.states.contains(State.RIDING), "never riding");
        check(s.parked.isEmpty(), "no park");
        check(s.d.state() == State.IDLE, "probe ended, back to IDLE");
    }

    static void cafeJitterNoSpeed() {
        System.out.println("cafe: jitter, no speed field");
        Sim s = new Sim();
        s.speedField = false;
        s.jitterM = 15; s.acc = 25;
        s.d.onMotion(s.t);
        s.stand(300);
        check(!s.states.contains(State.RIDING), "never riding");
        check(s.d.state() == State.IDLE, "back to IDLE");
    }

    static void walkOnly() {
        System.out.println("walk only: no ride, no park");
        Sim s = new Sim();
        s.d.onMotion(s.t);
        s.walk(600, 1.4);
        check(!s.states.contains(State.RIDING), "never riding");
        check(s.parked.isEmpty(), "no park");
        Sim n = new Sim();
        n.speedField = false;
        n.d.onMotion(n.t);
        n.walk(600, 1.5);
        check(!n.states.contains(State.RIDING), "never riding, no speed field");
        check(n.parked.isEmpty(), "no park, no speed field");
    }

    static Sim parkedSim() {
        Sim s = new Sim();
        s.d.onMotion(s.t);
        s.ride(60, 9);
        s.brake();
        s.stand(10);
        s.walk(100, 1.4);
        return s;
    }

    static void rideParkRideSameSpot() {
        System.out.println("ride, park, come back, ride from the same spot: left");
        Sim s = parkedSim();
        check(s.parked.size() == 1, "parked first");
        double[] p = s.parked.get(0);
        s.heading = 180;                       // walk back
        while (ParkDetector.dist(s.lat, s.lng, p[0], p[1]) > 5) s.walk(5, 1.4);
        s.heading = 90;
        s.stand(20);
        check(s.left == 0, "standing at the bike is not leaving");
        s.ride(45, 8);
        check(s.left == 1, "ride from within 150 m marks it left");
        check(!s.d.hasCurrentPark(), "no current park");
        check(s.d.state() == State.RIDING, "riding");
    }

    static void rideParkRideFarAway() {
        System.out.println("park, walk 500 m, ride from there: not left");
        Sim s = parkedSim();
        s.walk(240, 1.4);
        s.walk(600, 1.4);   // trail over, GPS would be off; sensor says motion again
        s.d.onMotion(s.t);
        s.ride(70, 8);
        check(s.d.state() == State.RIDING, "riding");
        check(s.left == 0, "the park stays current");
        check(s.d.hasCurrentPark(), "has current park");
    }

    static void probeExpires() {
        System.out.println("motion with nothing after it: probe ends");
        Sim s = new Sim();
        s.d.onMotion(s.t);
        check(s.d.state() == State.PROBE, "probe");
        s.tickTo(s.t + 125_000);
        check(s.d.state() == State.IDLE, "IDLE after two minutes");
    }

    static void poorFixesIgnored() {
        System.out.println("fixes worse than 50 m do not count");
        Sim s = new Sim();
        s.acc = 80;
        s.d.onMotion(s.t);
        s.ride(60, 12);
        check(s.d.state() != State.RIDING, "no ride from 80 m fixes");
    }

    static void stepsNeeded() {
        System.out.println("12 steps and a minute");
        Sim s = new Sim();
        s.d.onMotion(s.t);
        s.ride(60, 9);
        s.brake();
        s.go(7, 0, 1.4);                       // 9 steps
        s.stand(120);
        check(s.parked.isEmpty(), "9 steps do not park");
        Sim w = new Sim();
        w.d.onMotion(w.t);
        w.ride(60, 9);
        double[] stop = w.brake();
        w.go(10, 0.8, 1.5);                    // 15 steps in the first seconds
        w.stand(30);
        check(w.parked.isEmpty(), "steps at once, but not a minute yet");
        w.stand(40);
        check(w.parked.size() == 1, "parked once the minute is up");
        if (w.parked.size() == 1) check((long) w.parked.get(0)[3] == (long) stop[2], "park time is the stop time");
    }

    static void trailLimits() {
        System.out.println("trail stops at 1.5 km");
        Sim s = parkedSim();
        s.go(420, 4.0, 2.5);                   // jog away
        check(s.trailEnds == 1, "trail ended before 15 minutes");
        double len = 0;
        for (int i = 1; i < s.trail.size(); i++)
            len += ParkDetector.dist(s.trail.get(i - 1)[0], s.trail.get(i - 1)[1], s.trail.get(i)[0], s.trail.get(i)[1]);
        check(len > 1450 && len < 1700, "trail length about 1.5 km (" + Math.round(len) + " m)");
    }

    static void walkWhileStopped() {
        System.out.println("brisk walk away (2.2 m/s) from the stop still parks at the stop");
        Sim s = new Sim();
        s.d.onMotion(s.t);
        s.ride(60, 9);
        double[] stop = s.brake();
        s.go(80, 2.2, 2.2);
        check(s.parked.size() == 1, "parked");
        if (s.parked.size() == 1)
            check(ParkDetector.dist(s.parked.get(0)[0], s.parked.get(0)[1], stop[0], stop[1]) < 12, "at the stop point");
    }

    static void coarseFallback() {
        System.out.println("no motion sensor: two coarse fixes far apart start a probe");
        Sim s = new Sim();
        s.d.onCoarseFix(18.79, 98.98, 40, s.t);
        s.d.onCoarseFix(18.7905, 98.9802, 40, s.t + 120_000);
        check(s.d.state() == State.IDLE, "60 m is not motion");
        s.d.onCoarseFix(18.7990, 98.9802, 40, s.t + 240_000);
        check(s.d.state() == State.PROBE, "1 km is");
    }
}
