package net.motdang.app;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** What the app was opened with → the motdang.net page to show. Plain Java, so test/ can run it. */
final class Links {
    static final String HOME = "https://motdang.net/";
    /** What the app opens on: the map with your bike, parking and toilets. */
    static final String START = "https://motdang.net/appmap/";

    private Links() {}

    static String pageFor(String action, String data, String text, String subject) {
        if ("android.intent.action.SEND".equals(action) && text != null && !text.trim().isEmpty()) {
            return map(text.trim(), subject);
        }
        if ("android.intent.action.VIEW".equals(action) && data != null) {
            if (data.startsWith("https://motdang.net/") || data.startsWith("https://www.motdang.net/")
                    || data.equals("https://motdang.net")) return data;
            if (data.regionMatches(true, 0, "geo:", 0, 4)) return map(geo(data), null);
        }
        return START;
    }

    /** geo:0,0?q=coffee is a search for "coffee"; geo:18.7,98.9 is a point. The map reads both. */
    static String geo(String g) {
        int q = g.indexOf("?q=");
        if (q < 0) q = g.indexOf("&q=");
        String head = g.substring(4, g.indexOf('?') < 0 ? g.length() : g.indexOf('?'));
        if (q >= 0 && (head.startsWith("0,0") || head.isEmpty())) {
            String v = g.substring(q + 3);
            int amp = v.indexOf('&');
            if (amp >= 0) v = v.substring(0, amp);
            try { return URLDecoder.decode(v, "UTF-8"); } catch (Exception e) { return v; }
        }
        return g;
    }

    static String map(String text, String subject) {
        String u = HOME + "map.html?st=" + enc(text.length() > 500 ? text.substring(0, 500) : text);
        if (subject != null && !subject.trim().isEmpty()) u += "&sn=" + enc(subject.trim());
        return u;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; }
    }
}
