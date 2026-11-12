package se.seklof.nattlogg;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Appens huvudskärm: Nattskyller-sidan i en webbvy, med en brygga ("Natt") som ger sidan
 * det en webbsida inte får göra själv: riktiga larm, wifi-loggen i telefonen och knappar på låsskärmen.
 */
public class WebActivity extends Activity {
    private static final int REQ_LOG = 7;
    private static final int REQ_NOTE = 8;
    private static WeakReference<WebActivity> current = new WeakReference<>(null);

    private WebView web;
    private boolean visible;
    private long loadedAt;

    /** Be sidan hämta nya händelser (larm kvitterat, Klar/Paus) om den är framme just nu. */
    static void poke() {
        final WebActivity a = current.get();
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (a.visible && a.web != null) a.web.evaluateJavascript("window.nattResume&&window.nattResume()", null);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new WeakReference<>(this);
        Www.init(this);
        getWindow().setStatusBarColor(0xFF0E1419);
        getWindow().setNavigationBarColor(0xFF0E1419);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E1419);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(), "Natt");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!"GET".equals(request.getMethod()) || !Www.HOST.equals(u.getHost())) return null;
                return Www.open(getApplicationContext(), u.getPath(), new Www.Changed() {
                    @Override
                    public void onChanged(String rel) {
                        // En nyare sida hittades. Har appen nyss öppnats: visa den direkt, annars nästa gång.
                        if (!"index.html".equals(rel)) return;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (visible && SystemClock.elapsedRealtime() - loadedAt < 20_000L) web.reload();
                            }
                        });
                    }
                });
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (Www.HOST.equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith(Www.PREFIX)) return false;
                // Allt annat öppnas i telefonens webbläsare, aldrig i appen.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loadedAt = SystemClock.elapsedRealtime();
            }
        });
        setContentView(web);
        loadedAt = SystemClock.elapsedRealtime();
        web.loadUrl(Www.BASE + "index.html");

        Alarms.restoreAll(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        web.onResume();
        autoLog();
        web.evaluateJavascript("window.nattResume&&window.nattResume()", null);
        // Varje gång appen kommer fram: finns en nyare sida på webben laddas den in direkt.
        Www.checkNow(getApplicationContext(), new Www.Changed() {
            @Override
            public void onChanged(String rel) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (visible && web != null) web.reload();
                    }
                });
            }
        });
    }

    @Override
    protected void onPause() {
        visible = false;
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current = new WeakReference<>(null);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------- Wifi-loggningen ----------

    private boolean hasLogPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationOn() {
        if (Build.VERSION.SDK_INT < 28) return true;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return lm == null || lm.isLocationEnabled();
    }

    /** Öppnas appen under passet (16:00–04:30) startar loggningen själv, om den inte stoppats för hand i natt. */
    private void autoLog() {
        SharedPreferences p = Store.prefs(this);
        if (Store.isLogging(this)) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY), m = Calendar.getInstance().get(Calendar.MINUTE);
        boolean shift = h >= 16 || h < 4 || (h == 4 && m < 30);
        if (!shift) return;
        if (Store.shiftDate(System.currentTimeMillis()).equals(p.getString("autoOff", ""))) return;
        if (!hasLogPermissions() || !locationOn()) return;
        try {
            startForegroundService(new Intent(this, LogService.class));
        } catch (Exception ignored) {
        }
    }

    private void startLogging() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_LOG);
            return;
        }
        if (!locationOn()) {
            toast("Slå på Plats i telefonens snabbmeny – annars får appen inte läsa av wifi");
            return;
        }
        Store.prefs(this).edit().putString("autoOff", "").apply();
        try {
            startForegroundService(new Intent(this, LogService.class));
            toast("Loggningen är igång");
        } catch (Exception e) {
            toast("Kunde inte starta loggningen");
        }
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("window.nattResume&&window.nattResume()", null);
            }
        }, 600);
    }

    private void stopLogging() {
        stopService(new Intent(this, LogService.class));
        Store.prefs(this).edit().putBoolean("running", false)
                .putString("autoOff", Store.shiftDate(System.currentTimeMillis())).apply();
        toast("Loggningen stoppad");
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("window.nattResume&&window.nattResume()", null);
            }
        }, 600);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOG) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLogging();
        } else {
            toast("Utan platsbehörighet (exakt plats) får appen inte läsa av wifi-sändare");
        }
    }

    // ---------- Bryggan till sidan ----------
    // Metoderna anropas från sidans JavaScript på en egen tråd, inte på skärmtråden.

    private static long num(String s) {
        try {
            return (long) Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    final class Bridge {
        @JavascriptInterface
        public int version() {
            return 1;
        }

        /** Arkiv och nyckel som redan är inlagda för loggen, så att backupen i sidan slipper fråga igen. */
        @JavascriptInterface
        public String gh() {
            try {
                SharedPreferences p = Store.prefs(WebActivity.this);
                return new JSONObject().put("repo", p.getString("repo", Store.DEFAULT_REPO)).put("token", p.getString("token", "")).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String status() {
            try {
                SharedPreferences p = Store.prefs(WebActivity.this);
                long now = System.currentTimeMillis();
                boolean tonight = Store.shiftDate(now).equals(p.getString("shift", ""));
                return new JSONObject()
                        .put("logging", Store.isLogging(WebActivity.this))
                        .put("scans", tonight ? p.getInt("scans", 0) : 0)
                        .put("lastScan", tonight ? p.getLong("lastScan", 0) : 0)
                        .put("lastErr", p.getString("lastErr", ""))
                        .put("exact", Alarms.canExact(WebActivity.this))
                        .put("ringing", AlarmService.ringing(WebActivity.this))
                        .toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void logStart() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startLogging();
                }
            });
        }

        @JavascriptInterface
        public void logStop() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopLogging();
                }
            });
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(WebActivity.this, MainActivity.class));
                }
            });
        }

        /** Nattens wifi-avläsningar från och med rad "from": {"n":antal,"rows":[...]}. */
        @JavascriptInterface
        public String rows(String shift, int from) {
            return Store.rows(WebActivity.this, shift, Math.max(0, from));
        }

        @JavascriptInterface
        public void setAlarm(String id, String atMs, String kind, String title, String text, String nextMs) {
            if (id == null || id.isEmpty()) return;
            Alarms.set(getApplicationContext(), id, num(atMs), kind == null ? "pizza" : kind,
                    title == null ? "Larm" : title, text == null ? "" : text, num(nextMs));
        }

        @JavascriptInterface
        public void cancelAlarm(String id) {
            if (id == null || id.isEmpty()) return;
            Alarms.cancel(getApplicationContext(), id);
        }

        /** Tysta larmet som ringer. Med ett namn tystas det bara om det är just det larmet. */
        @JavascriptInterface
        public void stopRing(String id) {
            String now = AlarmService.ringing(WebActivity.this);
            if (now.isEmpty()) return;
            if (id == null || id.isEmpty() || id.equals(now)) AlarmService.stop(getApplicationContext());
        }

        @JavascriptInterface
        public void runNote(String key, String name, boolean paused) {
            if (key == null || key.isEmpty()) return;
            RunNote.show(getApplicationContext(), key, name == null ? "" : name, paused);
        }

        @JavascriptInterface
        public void clearRunNote() {
            RunNote.clear(getApplicationContext());
        }

        @JavascriptInterface
        public String takeEvents() {
            return Store.takeEvents(WebActivity.this);
        }
    }
}
