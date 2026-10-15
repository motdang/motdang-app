import net.motdang.app.Park;
import net.motdang.app.ParkBook;
import net.motdang.app.ParkText;

/** java -cp build/test ParkBookTest  (build.sh runs it) */
public class ParkBookTest {
    static int failed = 0;
    static void check(boolean ok, String what) {
        if (ok) System.out.println("  ok   " + what);
        else { failed++; System.out.println("  FAIL " + what); }
    }
    static Park p(long t, String how) { return new Park(18.79, 98.98, 8, t, how, "bike"); }

    public static void main(String[] a) {
        ParkBook b = new ParkBook();
        b.add(p(100, Park.TOLD));
        b.add(p(200, Park.GUESSED));
        check(b.current.t == 200 && b.history.size() == 1 && b.history.get(0).t == 100, "a new park pushes the old one to history");
        check(b.notMine(999) == false && b.current.t == 200, "not-mine with another time does nothing");
        check(b.notMine(200) && b.current.t == 100 && b.history.isEmpty(), "not-mine drops the guess and brings the told park back");

        b = new ParkBook();
        b.add(p(100, Park.GUESSED));
        b.leave(150);
        check(b.current == null && b.history.get(0).left == 1 && b.history.get(0).leftT == 150, "leave moves it to history, marked");
        b.add(p(300, Park.GUESSED));
        check(b.notMine(0) && b.current == null && b.history.size() == 1, "a left park does not come back");

        b = new ParkBook();
        b.add(p(100, Park.TOLD));
        check(!b.notMine(0), "a told park is not a guess");

        b = new ParkBook();
        check(b.setNote("level 2, by the lift", "2") == false, "no note without a park");
        b.add(p(1, Park.TOLD));
        check(b.setNote("  by the lift ", "B2") && b.current.note.equals("by the lift") && b.current.level.equals("B2"), "note and level");
        check(b.addTrail(1, 18.8, 98.9, 5) && b.current.trail.size() == 2, "trail point");
        check(!b.addTrail(2, 18.8, 98.9, 5), "trail point for another park is refused");

        b = new ParkBook();
        for (int i = 0; i < 70; i++) { Park q = p(i, Park.GUESSED); q.trail.add(new double[]{1, 2, 3}); b.add(q); }
        check(b.history.size() == 60, "history keeps 60");
        check(b.history.get(0).trail.size() == 2 && b.history.get(5).trail.size() == 1, "old history entries keep the park point only");

        check(ParkText.elapsed(47 * 60_000L).equals("47 min"), "47 min");
        check(ParkText.elapsed(65 * 60_000L).equals("1 h 05 min"), "1 h 05 min");
        check(ParkText.elapsed(27 * 3_600_000L).equals("1 d 3 h"), "1 d 3 h");
        check(ParkText.ongoingTitle("bike", "14:32", 47 * 60_000L).equals("รถจอดอยู่ · Bike parked 14:32 · 47 min"), "ongoing title");
        check(ParkText.guessTitle("14:32").equals("จอดตรงนี้ 14:32 · Parked here 14:32"), "guess title");
        System.out.println(failed == 0 ? "\nall passed" : "\n" + failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
}
