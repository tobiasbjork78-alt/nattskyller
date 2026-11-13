package se.seklof.nattlogg;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Nattskyller-sidan som appen visar. Sidan ligger inbyggd i appen och öppnas därför direkt, även utan nät.
 * I bakgrunden hämtas den senaste versionen från webben och sparas i telefonen, så att ändringar
 * i sidan kommer fram utan att appen behöver installeras om.
 */
final class Www {
    static final String HOST = "tobiasbjork78-alt.github.io";
    static final String PREFIX = "/nattskyller/nattskyller/";
    static final String BASE = "https://" + HOST + PREFIX;
    private static final Map<String, Long> lastCheck = new HashMap<>();

    interface Changed {
        void onChanged(String rel);
    }

    private Www() {}

    private static File dir(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "www");
    }

    /** En ny appversion har en nyare inbyggd sida än den som sparats tidigare: börja om från den. */
    static void init(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            long ver = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            SharedPreferences p = Store.prefs(c);
            if (p.getLong("wwwVer", -1) != ver) {
                wipe(dir(c));
                p.edit().putLong("wwwVer", ver).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private static void wipe(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) wipe(x);
        f.delete();
    }

    private static String mime(String rel) {
        String r = rel.toLowerCase();
        if (r.endsWith(".html")) return "text/html";
        if (r.endsWith(".js")) return "application/javascript";
        if (r.endsWith(".css")) return "text/css";
        if (r.endsWith(".json") || r.endsWith(".webmanifest")) return "application/json";
        if (r.endsWith(".jpg") || r.endsWith(".jpeg")) return "image/jpeg";
        if (r.endsWith(".png")) return "image/png";
        if (r.endsWith(".svg")) return "image/svg+xml";
        if (r.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static byte[] local(Context c, String rel) {
        try {
            File f = new File(dir(c), rel);
            if (f.isFile() && f.length() > 0) return readAll(new FileInputStream(f));
        } catch (Exception ignored) {
        }
        try {
            return readAll(c.getAssets().open(rel));
        } catch (Exception ignored) {
        }
        return null;
    }

    private static byte[] fetch(String rel, int timeoutMs) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(BASE + rel).openConnection();
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(timeoutMs);
            con.setUseCaches(false);
            con.setRequestProperty("User-Agent", "nattskyller-app");
            if (con.getResponseCode() != 200) return null;
            byte[] b = readAll(con.getInputStream());
            return b.length > 0 ? b : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static void save(Context c, String rel, byte[] data) {
        try {
            File f = new File(dir(c), rel);
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(data);
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception ignored) {
        }
    }

    /** Svar till webbvyn för en fil i sidan, eller null om adressen inte hör till sidan. */
    static WebResourceResponse open(final Context c, String path, final Changed changed) {
        if (path == null || !path.startsWith(PREFIX)) return null;
        String r = path.substring(PREFIX.length());
        if (r.isEmpty()) r = "index.html";
        if (r.contains("..") || r.startsWith("/")) return null;
        final String rel = r;

        byte[] data = local(c, rel);
        if (data == null) {
            // Finns varken inbyggd eller sparad: hämta nu.
            data = fetch(rel, 8000);
            if (data != null) save(c, rel, data);
        } else {
            refreshLater(c, rel, data, changed);
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        if (data == null) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, new ByteArrayInputStream(new byte[0]));
        }
        String m = mime(rel);
        boolean text = m.startsWith("text/") || m.contains("javascript") || m.contains("json") || m.contains("svg");
        return new WebResourceResponse(m, text ? "utf-8" : null, 200, "OK", headers, new ByteArrayInputStream(data));
    }

    /**
     * Titta efter en nyare sida nu (högst en gång i minuten). Används varje gång appen kommer fram:
     * loggningen håller appen vid liv hela natten, så utan det här skulle sidan aldrig laddas om.
     */
    static void checkNow(final Context c, final Changed changed) {
        final String rel = "index.html";
        long now = System.currentTimeMillis();
        synchronized (lastCheck) {
            Long last = lastCheck.get(rel);
            if (last != null && now - last < 60_000L) return;
            lastCheck.put(rel, now);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] current = local(c, rel);
                byte[] fresh = fetch(rel, 10000);
                if (fresh == null || current == null || Arrays.equals(fresh, current)) return;
                String tail = new String(fresh, Math.max(0, fresh.length - 200), Math.min(200, fresh.length), java.nio.charset.StandardCharsets.UTF_8);
                if (!tail.contains("</html>")) return;
                save(c, rel, fresh);
                // övriga sidor i samma mapp hämtas om nästa gång de öppnas
                synchronized (lastCheck) {
                    lastCheck.remove("repris.html");
                    lastCheck.remove("sensor.html");
                    lastCheck.remove("var.html");
                }
                if (changed != null) changed.onChanged(rel);
            }
        }).start();
    }

    /** Titta efter en nyare version i bakgrunden, högst var femte minut för sidan och en gång i timmen för övrigt. */
    private static void refreshLater(final Context c, final String rel, final byte[] current, final Changed changed) {
        long now = System.currentTimeMillis();
        long every = rel.equals("index.html") ? 5L * 60_000L : 60L * 60_000L;
        synchronized (lastCheck) {
            Long last = lastCheck.get(rel);
            if (last != null && now - last < every) return;
            lastCheck.put(rel, now);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] fresh = fetch(rel, 10000);
                if (fresh == null || Arrays.equals(fresh, current)) return;
                // En halv sida får aldrig ersätta en hel: kräv att den ser komplett ut.
                if (rel.equals("index.html")) {
                    String tail = new String(fresh, Math.max(0, fresh.length - 200), Math.min(200, fresh.length), java.nio.charset.StandardCharsets.UTF_8);
                    if (!tail.contains("</html>")) return;
                }
                save(c, rel, fresh);
                if (changed != null) changed.onChanged(rel);
            }
        }).start();
    }
}
