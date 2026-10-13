package net.motdang.app;

/** The words on the parking notifications. Plain Java, tested on the Mac. */
public final class ParkText {
    private ParkText() {}

    /** 47 min, 1 h 05 min, 2 d 3 h. */
    public static String elapsed(long ms) {
        long m = Math.max(0, ms) / 60_000;
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 24) return h + " h " + (m % 60 < 10 ? "0" : "") + (m % 60) + " min";
        return (h / 24) + " d " + (h % 24) + " h";
    }

    /** clock is the park time as HH:mm. */
    public static String guessTitle(String clock) {
        return "จอดตรงนี้ " + clock + " · Parked here " + clock;
    }

    public static String ongoingTitle(String vehicle, String clock, long sinceMs) {
        String v = Park.CAR.equals(vehicle) ? "Car" : "Bike";
        return "รถจอดอยู่ · " + v + " parked " + clock + " · " + elapsed(sinceMs);
    }

    public static final String IDLE_TITLE = "มดแดงจำที่จอดให้ · Mot Dang remembers where you park";
    public static final String GUESS_TEXT = "แตะดูว่าจอดตรงไหน · Tap to see where.";
}
