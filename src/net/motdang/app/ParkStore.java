package net.motdang.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The parks, kept in SharedPreferences "park" as one JSON string (key "book") so
 * a change is one write. The rules are in ParkBook; this reads and writes it.
 * Every call takes the class lock: the service, the bridge and the notification
 * receiver all use it.
 */
final class ParkStore {
    private static final String PREFS = "park", BOOK = "book", WATCH = "watch";

    private ParkStore() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean watching(Context c) { return prefs(c).getBoolean(WATCH, false); }

    static void setWatching(Context c, boolean on) { prefs(c).edit().putBoolean(WATCH, on).apply(); }

    // ---- changes: each returns what the caller needs, and saves ----

    static synchronized Park guess(Context c, double lat, double lng, double acc, long t) {
        ParkBook b = load(c);
        Park p = b.add(new Park(lat, lng, acc, t, Park.GUESSED, Park.BIKE));
        save(c, b);
        return p;
    }

    static synchronized Park told(Context c, double lat, double lng, double acc, String vehicle, String note) {
        ParkBook b = load(c);
        Park p = new Park(lat, lng, acc, System.currentTimeMillis(), Park.TOLD, vehicle);
        p.note = ParkBook.clip(note, 300);
        b.add(p);
        save(c, b);
        return p;
    }

    static synchronized boolean note(Context c, String note, String level) {
        ParkBook b = load(c);
        boolean ok = b.setNote(note, level);
        if (ok) save(c, b);
        return ok;
    }

    /** Found it, or the vehicle was taken. */
    static synchronized Park leave(Context c, long now) {
        ParkBook b = load(c);
        Park p = b.leave(now);
        if (p != null) save(c, b);
        return p;
    }

    static synchronized boolean notMine(Context c, long t) {
        ParkBook b = load(c);
        boolean ok = b.notMine(t);
        if (ok) save(c, b);
        return ok;
    }

    static synchronized void trail(Context c, long parkT, double lat, double lng, long t) {
        ParkBook b = load(c);
        if (b.addTrail(parkT, lat, lng, t)) save(c, b);
    }

    // ---- reads ----

    static synchronized Park current(Context c) { return load(c).current; }

    static synchronized String currentJson(Context c) {
        Park p = load(c).current;
        return p == null ? "" : json(p).toString();
    }

    static synchronized String historyJson(Context c) {
        JSONArray a = new JSONArray();
        for (Park p : load(c).history) a.put(json(p));
        return a.toString();
    }

    // ---- JSON ----

    static JSONObject json(Park p) {
        try {
            JSONObject o = new JSONObject();
            o.put("lat", round(p.lat, 6)).put("lng", round(p.lng, 6)).put("acc", Math.round(p.acc));
            o.put("t", p.t).put("how", p.how).put("vehicle", p.vehicle).put("note", p.note).put("level", p.level);
            JSONArray tr = new JSONArray();
            for (double[] q : p.trail) tr.put(new JSONArray().put(round(q[0], 6)).put(round(q[1], 6)).put((long) q[2]));
            o.put("trail", tr).put("left", p.left);
            if (p.left == 1) o.put("leftT", p.leftT);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static Park park(JSONObject o) {
        Park p = new Park();
        p.lat = o.optDouble("lat");
        p.lng = o.optDouble("lng");
        p.acc = o.optDouble("acc", 0);
        p.t = o.optLong("t");
        p.how = o.optString("how", Park.GUESSED);
        p.vehicle = o.optString("vehicle", Park.BIKE);
        p.note = o.optString("note", "");
        p.level = o.optString("level", "");
        p.left = o.optInt("left", 0);
        p.leftT = o.optLong("leftT", 0);
        JSONArray tr = o.optJSONArray("trail");
        if (tr != null) for (int i = 0; i < tr.length(); i++) {
            JSONArray q = tr.optJSONArray(i);
            if (q != null && q.length() >= 3) p.trail.add(new double[]{q.optDouble(0), q.optDouble(1), q.optLong(2)});
        }
        return p;
    }

    private static double round(double v, int places) {
        double m = Math.pow(10, places);
        return Math.round(v * m) / m;
    }

    private static ParkBook load(Context c) {
        ParkBook b = new ParkBook();
        try {
            JSONObject o = new JSONObject(prefs(c).getString(BOOK, "{}"));
            JSONObject cur = o.optJSONObject("cur");
            if (cur != null) b.current = park(cur);
            JSONArray h = o.optJSONArray("hist");
            if (h != null) for (int i = 0; i < h.length(); i++) {
                JSONObject q = h.optJSONObject(i);
                if (q != null) b.history.add(park(q));
            }
        } catch (Exception e) {
            // unreadable: start empty
        }
        return b;
    }

    private static void save(Context c, ParkBook b) {
        try {
            JSONObject o = new JSONObject();
            if (b.current != null) o.put("cur", json(b.current));
            JSONArray h = new JSONArray();
            for (Park p : b.history) h.put(json(p));
            o.put("hist", h);
            prefs(c).edit().putString(BOOK, o.toString()).apply();
        } catch (Exception e) {
            // keep what was there
        }
    }

    static String clock(long t) {
        return new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new java.util.Date(t));
    }
}
