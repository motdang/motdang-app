import java.lang.reflect.Method;

/** java -cp build/classes:test LinksTest  (build.sh runs it) */
public class LinksTest {
    static int failed = 0;
    static void eq(String got, String want, String what) {
        if (want.equals(got)) System.out.println("  ok   " + what);
        else { failed++; System.out.println("  FAIL " + what + "\n       got  " + got + "\n       want " + want); }
    }
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("net.motdang.app.Links");
        Method m = c.getDeclaredMethod("pageFor", String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        String S = "android.intent.action.SEND", V = "android.intent.action.VIEW";
        eq((String) m.invoke(null, null, null, null, null), "https://motdang.net/appmap/", "opened plain: the map");
        eq((String) m.invoke(null, S, null, "Wat Phra Singh\nhttps://maps.app.goo.gl/DM78scCKekcnPGZf9", "Wat Phra Singh"),
           "https://motdang.net/map.html?st=Wat+Phra+Singh%0Ahttps%3A%2F%2Fmaps.app.goo.gl%2FDM78scCKekcnPGZf9&sn=Wat+Phra+Singh",
           "a Google Maps share lands on the map");
        eq((String) m.invoke(null, V, "geo:18.7876,98.9935", null, null),
           "https://motdang.net/map.html?st=geo%3A18.7876%2C98.9935", "geo: point");
        eq((String) m.invoke(null, V, "geo:0,0?q=%E0%B8%A7%E0%B8%B1%E0%B8%94", null, null),
           "https://motdang.net/map.html?st=%E0%B8%A7%E0%B8%B1%E0%B8%94", "geo: search is the words");
        eq((String) m.invoke(null, V, "https://motdang.net/cm/p/x.html", null, null),
           "https://motdang.net/cm/p/x.html", "a motdang link opens as itself");
        eq((String) m.invoke(null, V, "https://evil.example/motdang.net/", null, null),
           "https://motdang.net/appmap/", "another site's link does not load in the window");
        System.out.println(failed == 0 ? "\nall passed" : "\n" + failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
}
