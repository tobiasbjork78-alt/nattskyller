package se.seklof.nattlogg;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Båtens läge från telefonens GPS. Inne i skrovet når satelliterna sällan fram, så appen sparar
 * senaste läget med fart och kurs, och sidan räknar själv framåt därifrån.
 *
 * Två sätt att lyssna:
 *  - live: medan sidan "Var är vi?" är framme, ett läge varannan sekund.
 *  - burst: loggtjänsten provar en kort stund var femte minut, så att läget inte hinner bli gammalt.
 */
final class Gps {
    private static final String PREFS = "gps";
    private static final long TRACK_GAP_MS = 60_000L;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private static Listener live;
    private static Listener burst;
    private static long lastTrack;

    private Gps() {
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static LocationManager lm(Context c) {
        return (LocationManager) c.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
    }

    private static final class Listener implements LocationListener {
        final Context app;
        final boolean isBurst;
        int fixes;

        Listener(Context app, boolean isBurst) {
            this.app = app;
            this.isBurst = isBurst;
        }

        @Override
        public void onLocationChanged(Location l) {
            if (l == null) return;
            save(app, l);
            fixes++;
            // Fart och kurs blir pålitliga först efter några lägen i rad.
            if (isBurst && fixes >= 5) burstStop(app);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    }

    /** Anropas på skärmtråden. */
    static void liveStart(Context c) {
        if (live != null || !allowed(c)) return;
        Context app = c.getApplicationContext();
        burstStop(app);
        LocationManager m = lm(app);
        if (m == null) return;
        try {
            Location old = m.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (old != null && old.getTime() > prefs(app).getLong("t", 0)) save(app, old);
            live = new Listener(app, false);
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, live, Looper.getMainLooper());
        } catch (Exception e) {
            live = null;
        }
    }

    static void liveStop(Context c) {
        if (live == null) return;
        try {
            LocationManager m = lm(c);
            if (m != null) m.removeUpdates(live);
        } catch (Exception ignored) {
        }
        live = null;
    }

    /** Lyssna en kort stund och ge upp om inget hörs. Anropas på skärmtråden. */
    static void burst(Context c, long ms) {
        if (live != null || burst != null || !allowed(c)) return;
        final Context app = c.getApplicationContext();
        LocationManager m = lm(app);
        if (m == null) return;
        try {
            burst = new Listener(app, true);
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, burst, Looper.getMainLooper());
            H.postDelayed(new Runnable() {
                @Override
                public void run() {
                    burstStop(app);
                }
            }, ms);
        } catch (Exception e) {
            burst = null;
        }
    }

    private static void burstStop(Context c) {
        if (burst == null) return;
        try {
            LocationManager m = lm(c);
            if (m != null) m.removeUpdates(burst);
        } catch (Exception ignored) {
        }
        burst = null;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void save(Context c, Location l) {
        // Dåliga lägen (mer än 150 m osäkra) får inte skriva över ett bra som är färskt.
        SharedPreferences p = prefs(c);
        float acc = l.hasAccuracy() ? l.getAccuracy() : 999f;
        long t = l.getTime();
        if (acc > 150f && t - p.getLong("t", 0) < 120_000L) return;
        p.edit()
                .putLong("la", Double.doubleToRawLongBits(l.getLatitude()))
                .putLong("lo", Double.doubleToRawLongBits(l.getLongitude()))
                .putFloat("spd", l.hasSpeed() ? l.getSpeed() : -1f)
                .putFloat("brg", l.hasBearing() ? l.getBearing() : -1f)
                .putFloat("acc", acc)
                .putLong("t", t)
                .apply();

        if (t - lastTrack < TRACK_GAP_MS) return;
        lastTrack = t;
        String line = "{\"t\":" + (t / 1000L) + ",\"la\":" + round(l.getLatitude(), 5) + ",\"lo\":" + round(l.getLongitude(), 5)
                + ",\"s\":" + (l.hasSpeed() ? round(l.getSpeed(), 1) : -1) + ",\"b\":" + (l.hasBearing() ? Math.round(l.getBearing()) : -1) + "}\n";
        try {
            File f = new File(c.getApplicationContext().getFilesDir(), "gps-" + Store.shiftDate(t) + ".jsonl");
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static double round(double v, int dec) {
        double k = Math.pow(10, dec);
        return Math.round(v * k) / k;
    }

    /** Senaste läget som JSON: {ok, on, live, t, lat, lon, spd (m/s), brg (grader), acc (m)}. Utan läge saknas t. */
    static String last(Context c) {
        try {
            SharedPreferences p = prefs(c);
            JSONObject o = new JSONObject();
            o.put("ok", allowed(c));
            LocationManager m = lm(c);
            boolean on = true;
            try {
                on = m != null && m.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch (Exception ignored) {
            }
            o.put("on", on);
            o.put("live", live != null);
            long t = p.getLong("t", 0);
            if (t > 0) {
                o.put("t", t);
                o.put("lat", Double.longBitsToDouble(p.getLong("la", 0)));
                o.put("lon", Double.longBitsToDouble(p.getLong("lo", 0)));
                o.put("spd", p.getFloat("spd", -1f));
                o.put("brg", p.getFloat("brg", -1f));
                o.put("acc", p.getFloat("acc", 999f));
            }
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Tar bort spår som är äldre än 30 dagar. */
    static void clean(Context c) {
        File[] fs = c.getApplicationContext().getFilesDir().listFiles();
        if (fs == null) return;
        long old = System.currentTimeMillis() - 30L * 86_400_000L;
        for (File f : fs) {
            if (f.getName().startsWith("gps-") && f.lastModified() < old) f.delete();
        }
    }
}
