package se.seklof.nattlogg;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Lokal lagring av avläsningar och uppladdning till det privata GitHub-arkivet. */
final class Store {
    static final String PREFS = "nattlogg";
    static final String DEFAULT_REPO = "tobiasbjork78-alt/nattskyller-data";
    private static final Object LOCK = new Object();

    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File pending(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "pending.jsonl");
    }

    /** Skiftdygnet byter kl 06:00, precis som i Nattskyller. */
    static String shiftDate(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms - 6L * 3600_000L));
    }

    static String clock(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }

    /** Nattens alla avläsningar ligger också kvar i telefonen, så att appen kan räkna tid per kök direkt. */
    private static File night(Context c, String shift) {
        return new File(c.getApplicationContext().getFilesDir(), "night-" + shift + ".jsonl");
    }

    static void append(Context c, String line) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try (FileOutputStream out = new FileOutputStream(pending(c), true)) {
                out.write(bytes);
            } catch (Exception ignored) {
            }
            try (FileOutputStream out = new FileOutputStream(night(c, shiftDate(System.currentTimeMillis())), true)) {
                out.write(bytes);
            } catch (Exception ignored) {
            }
        }
    }

    /** Raderna från och med nummer "from" för ett skiftdygn, som JSON: {"n":antal totalt,"rows":[...]}. */
    static String rows(Context c, String shift, int from) {
        if (shift == null || !shift.matches("\\d{4}-\\d{2}-\\d{2}")) return "{\"n\":0,\"rows\":[]}";
        StringBuilder sb = new StringBuilder(4096);
        int n = 0;
        boolean first = true;
        synchronized (LOCK) {
            File f = night(c, shift);
            if (f.exists()) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.charAt(0) != '{' || line.charAt(line.length() - 1) != '}') continue;
                        if (n >= from) {
                            if (!first) sb.append(',');
                            sb.append(line);
                            first = false;
                        }
                        n++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return "{\"n\":" + n + ",\"rows\":[" + sb + "]}";
    }

    /** Tar bort nattfiler som är äldre än fyra dygn. */
    static void cleanNights(Context c) {
        File[] fs = c.getApplicationContext().getFilesDir().listFiles();
        if (fs == null) return;
        long old = System.currentTimeMillis() - 4L * 24 * 3600_000L;
        for (File f : fs) {
            if (f.getName().startsWith("night-") && f.lastModified() < old) f.delete();
        }
    }

    /**
     * Går loggningen verkligen? Flaggan "running" kan bli kvar om systemet dödar tjänsten utan förvarning
     * (t.ex. när appen uppdateras). Därför krävs också ett livstecken från tjänsten de senaste minuterna.
     */
    static boolean isLogging(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("running", false) && System.currentTimeMillis() - p.getLong("beat", 0) < 150_000L;
    }

    // ---------- Händelser från aviseringar (larm kvitterat, Klar/Paus) som sidan hämtar när den är framme ----------
    private static final Object EVLOCK = new Object();

    static void pushEvent(Context c, JSONObject ev) {
        synchronized (EVLOCK) {
            SharedPreferences p = prefs(c);
            try {
                org.json.JSONArray arr = new org.json.JSONArray(p.getString("events", "[]"));
                arr.put(ev);
                p.edit().putString("events", arr.toString()).commit();
            } catch (Exception ignored) {
            }
        }
    }

    static String takeEvents(Context c) {
        synchronized (EVLOCK) {
            SharedPreferences p = prefs(c);
            String s = p.getString("events", "[]");
            if (!"[]".equals(s)) p.edit().putString("events", "[]").commit();
            return s;
        }
    }

    static long pendingBytes(Context c) {
        return pending(c).length();
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static HttpURLConnection open(Context c, String path, String method) throws Exception {
        SharedPreferences p = prefs(c);
        String repo = p.getString("repo", DEFAULT_REPO);
        HttpURLConnection con = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + path).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("Authorization", "Bearer " + p.getString("token", ""));
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        con.setRequestProperty("User-Agent", "nattlogg");
        return con;
    }

    /** Kontrollerar att nyckeln når arkivet. Returnerar HTTP-status, eller -1 utan nät. */
    static int test(Context c) {
        try {
            HttpURLConnection con = open(c, "", "GET");
            int code = con.getResponseCode();
            con.disconnect();
            return code;
        } catch (Exception e) {
            return -1;
        }
    }

    static String errText(int code) {
        if (code == 401) return "Nyckeln godkänns inte (401)";
        if (code == 403) return "Nyckeln får inte skriva till arkivet (403)";
        if (code == 404) return "Hittar inte arkivet (404)";
        if (code == -1) return "Inget nät";
        return "GitHub svarade " + code;
    }

    /**
     * Skickar allt som väntar som en ny fil: wifi/ÅÅÅÅ-MM-DD/HHMMSS.jsonl.
     * En ny fil per uppladdning gör att inget befintligt behöver läsas först.
     * Misslyckas det ligger raderna kvar och följer med nästa gång.
     */
    static boolean upload(Context c) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            File f = pending(c);
            if (!f.exists() || f.length() == 0) return true;
            if (p.getString("token", "").isEmpty()) {
                p.edit().putString("lastErr", "Ingen nyckel inlagd").apply();
                return false;
            }
            try {
                byte[] data = readAll(f);
                long now = System.currentTimeMillis();
                String name = "wifi/" + shiftDate(now) + "/" + new SimpleDateFormat("HHmmss", Locale.US).format(new Date(now)) + ".jsonl";
                JSONObject body = new JSONObject();
                body.put("message", "Wifi-logg " + shiftDate(now));
                body.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
                HttpURLConnection con = open(c, "/contents/" + name, "PUT");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = con.getResponseCode();
                con.disconnect();
                if (code == 200 || code == 201) {
                    // Ta bara bort det som faktiskt skickades; rader som kommit till under tiden ligger kvar.
                    byte[] cur = readAll(f);
                    try (FileOutputStream out = new FileOutputStream(f, false)) {
                        if (cur.length > data.length) out.write(cur, data.length, cur.length - data.length);
                    }
                    p.edit().putLong("lastUpload", now).putString("lastErr", "").apply();
                    return true;
                }
                p.edit().putString("lastErr", errText(code)).apply();
                return false;
            } catch (Exception e) {
                p.edit().putString("lastErr", "Inget nät – försöker igen").apply();
                return false;
            }
        }
    }
}
