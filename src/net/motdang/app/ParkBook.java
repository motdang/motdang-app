package net.motdang.app;

import java.util.ArrayList;

/**
 * The current park and the history, and the rules for changing them.
 * Plain Java, tested on the Mac. ParkStore keeps one of these in SharedPreferences.
 */
public final class ParkBook {
    public static final int MAX_HISTORY = 60;
    /** Only the newest history entries keep their trail. */
    public static final int TRAIL_KEEP = 3;

    public Park current;
    /** Newest first. */
    public final ArrayList<Park> history = new ArrayList<>();

    /** A new park. The one before it, if any, goes to history unchanged. */
    public Park add(Park p) {
        if (current != null) history.add(0, current);
        current = p;
        trim();
        return p;
    }

    /** The vehicle was taken or found: the current park moves to history. */
    public Park leave(long now) {
        Park p = current;
        if (p == null) return null;
        p.left = 1;
        p.leftT = now;
        current = null;
        history.add(0, p);
        trim();
        return p;
    }

    /**
     * Drops the current park when it is a guess (and, when t is not 0, the guess made at t).
     * The park before it comes back if it was still current when the guess replaced it.
     */
    public boolean notMine(long t) {
        if (current == null || !Park.GUESSED.equals(current.how)) return false;
        if (t != 0 && current.t != t) return false;
        current = null;
        if (!history.isEmpty() && history.get(0).left == 0) current = history.remove(0);
        return true;
    }

    public boolean setNote(String note, String level) {
        if (current == null) return false;
        current.note = clip(note, 300);
        current.level = clip(level, 20);
        return true;
    }

    public boolean addTrail(long parkT, double lat, double lng, long t) {
        if (current == null || current.t != parkT) return false;
        current.trail.add(new double[]{lat, lng, t});
        return true;
    }

    private void trim() {
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        for (int i = TRAIL_KEEP; i < history.size(); i++) {
            Park p = history.get(i);
            if (p.trail.size() > 1) {
                ArrayList<double[]> keep = new ArrayList<>();
                keep.add(p.trail.get(0));
                p.trail = keep;
            }
        }
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > n ? s.substring(0, n) : s;
    }
}
