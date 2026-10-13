package net.motdang.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * motdang.net, whole, in one window — plus the two things a web page cannot do:
 * keep sending a live location with the screen locked (LiveService), and take
 * shares from other apps (a Google Maps link, a LINE location, geo: links).
 *
 * The site sees window.MotDangApp and hands those jobs here (share_layer.py in
 * mot-dang). Links that leave motdang.net open in their own apps.
 */
public class MainActivity extends Activity {
    static final String HOME = "https://motdang.net/";
    private static final int REQ_GEO = 1, REQ_FILE = 2, REQ_NOTE = 3;

    private WebView web;
    private volatile boolean onSite = true;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, in) -> {
                Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " MotDangApp/0.2");
        web.addJavascriptInterface(new Bridge(), "MotDangApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (isSite(u)) return false;
                openOutside(u);
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                onSite = isSite(Uri.parse(url));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (!isSite(Uri.parse(origin))) { cb.invoke(origin, false, false); return; }
                if (hasLocation()) { cb.invoke(origin, true, false); return; }
                geoCb = cb;
                geoOrigin = origin;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, () -> {
                if (web.canGoBack()) web.goBack(); else finish();
            });
        }

        if (saved != null) web.restoreState(saved);
        else open(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ParkService.ensure(this);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        open(i);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /** What the app was opened with → the page to load. */
    private void open(Intent i) {
        web.loadUrl(Links.pageFor(i == null ? null : i.getAction(),
                i == null ? null : i.getDataString(),
                i == null ? null : i.getStringExtra(Intent.EXTRA_TEXT),
                i == null ? null : i.getStringExtra(Intent.EXTRA_SUBJECT)));
    }

    static boolean isSite(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        String h = u.getHost();
        return "motdang.net".equals(h) || "www.motdang.net".equals(h);
    }

    private void openOutside(Uri u) {
        try {
            if ("intent".equals(u.getScheme())) {
                Intent i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    String fb = i.getStringExtra("browser_fallback_url");
                    if (fb != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fb)));
                }
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            // nothing on the phone opens it; stay where we are
        }
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_GEO && geoCb != null) {
            geoCb.invoke(geoOrigin, hasLocation(), false);
            geoCb = null;
        }
        if (code == ParkSetup.REQ) ParkService.ensure(this);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCb = null;
        }
    }

    /** window.MotDangApp — answered only while the window is on motdang.net. */
    class Bridge {
        @JavascriptInterface
        public void share(String text) {
            if (!onSite || text == null) return;
            runOnUiThread(() -> {
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(s, "ส่ง · Send"));
            });
        }

        @JavascriptInterface
        public void startLive(String id, String tok, String until) {
            if (!onSite || !LiveService.ID.matcher(id == null ? "" : id).matches()) return;
            long u;
            try { u = Long.parseLong(until); } catch (Exception e) { return; }
            if (!hasLocation()) return;
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTE));
            }
            Intent i = new Intent(MainActivity.this, LiveService.class)
                    .putExtra("id", id).putExtra("tok", tok).putExtra("until", u);
            startForegroundService(i);
        }

        @JavascriptInterface
        public void stopLive() {
            if (!onSite) return;
            startService(new Intent(MainActivity.this, LiveService.class).setAction(LiveService.STOP));
        }

        @JavascriptInterface
        public String liveState() {
            if (!onSite) return "";
            SharedPreferences p = getSharedPreferences("live", Context.MODE_PRIVATE);
            String id = p.getString("id", "");
            long until = p.getLong("until", 0);
            if (id.isEmpty() || until <= System.currentTimeMillis()) return "";
            return "{\"id\":\"" + id + "\",\"until\":" + until + ",\"ok\":" + p.getLong("ok", 0) + "}";
        }

        // ---- parking (ParkService, ParkStore) ----

        /** The current park as JSON, or "" when there is none. */
        @JavascriptInterface
        public String parkState() {
            return onSite ? ParkStore.currentJson(MainActivity.this) : "";
        }

        /** She says where she parked. Numbers arrive as strings. Returns the park JSON, or "". */
        @JavascriptInterface
        public String parkHere(String lat, String lng, String acc, String vehicle, String note) {
            if (!onSite) return "";
            double la, lo, ac;
            try {
                la = Double.parseDouble(lat);
                lo = Double.parseDouble(lng);
            } catch (Exception e) { return ""; }
            if (!(la >= -90 && la <= 90 && lo >= -180 && lo <= 180)) return "";
            try { ac = Double.parseDouble(acc); } catch (Exception e) { ac = 0; }
            if (!(ac >= 0)) ac = 0;
            Park p = ParkStore.told(MainActivity.this, la, lo, ac, vehicle, note);
            ParkNotes.cancelGuess(MainActivity.this);
            ParkService.syncIfRunning();
            return ParkStore.json(p).toString();
        }

        @JavascriptInterface
        public String parkNote(String note, String level) {
            if (!onSite) return "";
            if (!ParkStore.note(MainActivity.this, note, level)) return "";
            ParkService.syncIfRunning();
            return ParkStore.currentJson(MainActivity.this);
        }

        /** Found it: the current park moves to history, marked left. Returns "1" or "0". */
        @JavascriptInterface
        public String parkClear() {
            if (!onSite) return "";
            Park p = ParkStore.leave(MainActivity.this, System.currentTimeMillis());
            ParkNotes.cancelGuess(MainActivity.this);
            ParkService.syncIfRunning();
            return p == null ? "0" : "1";
        }

        /** Drops the current park when it is a guess and brings the one before it back. Returns "1" or "0". */
        @JavascriptInterface
        public String parkNotMine() {
            if (!onSite) return "";
            boolean ok = ParkStore.notMine(MainActivity.this, 0);
            ParkNotes.cancelGuess(MainActivity.this);
            ParkService.syncIfRunning();
            return ok ? "1" : "0";
        }

        /** A JSON array, newest first. */
        @JavascriptInterface
        public String parkHistory() {
            return onSite ? ParkStore.historyJson(MainActivity.this) : "";
        }

        /** "1" turns watching on, "0" off. Returns parkWatchState(). */
        @JavascriptInterface
        public String parkWatch(String on) {
            if (!onSite) return "";
            boolean want = "1".equals(on);
            ParkStore.setWatching(MainActivity.this, want);
            if (want) ParkService.ensure(MainActivity.this); else ParkService.stop();
            return ParkSetup.state(MainActivity.this);
        }

        /** off, on, needs-location, needs-background, needs-activity or needs-battery. */
        @JavascriptInterface
        public String parkWatchState() {
            return onSite ? ParkSetup.state(MainActivity.this) : "";
        }

        /** The step parkSetup() would run: location, background, activity, notifications, battery, vivo or done. */
        @JavascriptInterface
        public String parkSetupNext() {
            return onSite ? ParkSetup.next(MainActivity.this) : "";
        }

        /** Runs the next step and returns its name ("done" when none is left). */
        @JavascriptInterface
        public String parkSetup() {
            if (!onSite) return "";
            String step = ParkSetup.next(MainActivity.this);
            runOnUiThread(() -> ParkSetup.run(MainActivity.this));
            return step;
        }

        /** One reminder at a time; it opens the parking page at #back. The alarm is inexact. */
        @JavascriptInterface
        public String parkRemind(String atMs, String text) {
            if (!onSite || text == null || text.trim().isEmpty()) return "";
            long at;
            try { at = (long) Double.parseDouble(atMs); } catch (Exception e) { return ""; }
            ParkReceiver.schedule(MainActivity.this, at, text.trim().length() > 200 ? text.trim().substring(0, 200) : text.trim());
            return "1";
        }

        @JavascriptInterface
        public String parkRemindClear() {
            if (!onSite) return "";
            ParkReceiver.clear(MainActivity.this);
            return "1";
        }

        /** Comma list of milliseconds, vibrate first: "40,60,40" is buzz, pause, buzz. */
        @JavascriptInterface
        public void vibrate(String pattern) {
            if (!onSite || pattern == null) return;
            String[] parts = pattern.split(",");
            long[] w = new long[Math.min(parts.length, 20) + 1];
            long total = 0;
            for (int k = 0; k < w.length - 1; k++) {
                long v;
                try { v = Long.parseLong(parts[k].trim()); } catch (Exception e) { return; }
                v = Math.max(0, Math.min(v, 1000));
                total += v;
                w[k + 1] = v;
            }
            if (w.length < 2 || total > 5000) return;
            try {
                Vibrator vib = Build.VERSION.SDK_INT >= 31
                        ? getSystemService(VibratorManager.class).getDefaultVibrator()
                        : getSystemService(Vibrator.class);
                vib.vibrate(VibrationEffect.createWaveform(w, -1));
            } catch (Exception e) {
                // no vibrator
            }
        }
    }
}
