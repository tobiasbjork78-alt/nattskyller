package se.seklof.nattlogg;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.util.HashSet;

/**
 * Knackningar på telefonen som knappar. Telefonen kan ligga kvar i fickan.
 *
 *   Tre knackningar  = leta efter ett nytt GPS-läge
 *   Fyra knackningar = "Släppt nu" (släppt från disken; kökstiderna räknas därifrån)
 *
 * Svaret kommer som vibration, på full styrka, och låter olika så att det inte går att ta fel:
 *   tre korta surr                = tre knackningar uppfattade, letar nu
 *   två långa surr                = nytt läge hittat
 *   ett mycket långt surr         = hittade inget (inne i båten, eller plats avstängd)
 *   fyra korta surr och ett långt = Släppt är satt
 *
 * Så känns en knackning igen: accelerometern läses 200 gånger i sekunden, och en knackning är ett
 * plötsligt hopp mellan två avläsningar. Gång och arbete ger mjuka rörelser som inte hoppar så.
 * Tre eller fyra hopp med 0,12–0,7 sekunder emellan, och tyst före och efter, räknas.
 * Fler eller färre räknas inte, så skrammel i fickan utlöser sällan något.
 */
final class Knock implements SensorEventListener {
    private static final String PREFS = "knock";
    static final float THR_DEFAULT = 6f;                  // m/s² mellan två avläsningar
    private static final long REFRACT_NS = 90_000_000L;   // efterdallring inom 90 ms hör till samma knackning
    private static final long GAP_MIN_NS = 120_000_000L;
    private static final long GAP_MAX_NS = 700_000_000L;
    private static final long SEEK_MS = 60_000L;

    // Vibrationssvaren: tider i ms, varannan paus och varannan surr (börjar med paus).
    private static final long[] BUZZ_LETAR = {0, 140, 110, 140, 110, 140};
    private static final long[] BUZZ_HITTAT = {0, 450, 180, 450};
    private static final long[] BUZZ_INGET = {0, 1100};
    private static final long[] BUZZ_SLAPPT = {0, 140, 110, 140, 110, 140, 110, 140, 300, 800};

    private static final HashSet<String> users = new HashSet<>();
    private static Knock inst;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Det sidan visar medan man provar.
    static volatile int peaks, triples, quads;
    static volatile long lastPeakMs, lastTripleMs, lastQuadMs;
    static volatile float lastMax;
    static volatile String lastResult = "";
    private static volatile long muteUntil;               // egna vibrationer ska inte räknas som knackningar

    private final Context app;
    private HandlerThread thread;
    private volatile float thr;
    private boolean has;
    private float px, py, pz;
    private long lastOverNs;
    private final long[] seq = new long[8];
    private int n;

    private Knock(Context app) {
        this.app = app;
        this.thr = prefs(app).getFloat("thr", THR_DEFAULT);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("on", true);
    }

    /** Loggtjänsten ("logg") och sidan ("sida") säger till när de vill ha knackningar. Lyssnar så länge någon vill. */
    static synchronized void want(Context c, String who, boolean on) {
        if (on) users.add(who);
        else users.remove(who);
        apply(c);
    }

    private static synchronized void apply(Context c) {
        boolean run = !users.isEmpty() && enabled(c);
        if (run && inst == null) {
            Knock k = new Knock(c.getApplicationContext());
            if (k.start()) inst = k;
        } else if (!run && inst != null) {
            inst.stop();
            inst = null;
        }
    }

    static synchronized void set(Context c, boolean on, float thr) {
        if (thr < 2f) thr = 2f;
        if (thr > 30f) thr = 30f;
        prefs(c).edit().putBoolean("on", on).putFloat("thr", thr).apply();
        if (inst != null) inst.thr = thr;
        apply(c);
    }

    static String state(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("on", enabled(c));
            o.put("thr", prefs(c).getFloat("thr", THR_DEFAULT));
            o.put("listening", inst != null);
            o.put("peaks", peaks);
            o.put("triples", triples);
            o.put("lastPeak", lastPeakMs);
            o.put("lastTriple", lastTripleMs);
            o.put("quads", quads);
            o.put("lastQuad", lastQuadMs);
            o.put("skarpt", users.contains("logg"));
            o.put("lastMax", Math.round(lastMax * 10f) / 10.0);
            o.put("result", lastResult);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean start() {
        try {
            SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc == null) return false;
            thread = new HandlerThread("knack");
            thread.start();
            // 200 avläsningar i sekunden, levererade i klump fem gånger i sekunden så att processorn får vila emellan.
            return sm.registerListener(this, acc, 5_000, 200_000, new Handler(thread.getLooper()));
        } catch (Exception e) {
            return false;
        }
    }

    private void stop() {
        try {
            SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            if (sm != null) sm.unregisterListener(this);
        } catch (Exception ignored) {
        }
        if (thread != null) thread.quitSafely();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        long t = e.timestamp;
        if (!has) {
            has = true;
            px = x;
            py = y;
            pz = z;
            return;
        }
        float d = Math.abs(x - px) + Math.abs(y - py) + Math.abs(z - pz);
        px = x;
        py = y;
        pz = z;
        if (SystemClock.elapsedRealtime() < muteUntil) {
            n = 0;
            lastOverNs = t;
            return;
        }
        long quiet = t - lastOverNs;
        if (d > thr) {
            if (quiet > REFRACT_NS) {
                if (quiet > GAP_MAX_NS) n = 0;
                if (n < seq.length) seq[n] = t;
                n++;
                peaks++;
                lastPeakMs = System.currentTimeMillis();
                lastMax = d;
            } else if (d > lastMax) {
                lastMax = d;
            }
            lastOverNs = t;
        } else if (n > 0 && quiet > GAP_MAX_NS) {
            // Det har varit tyst en stund: var det precis tre eller fyra, med lagom mellanrum?
            if (n == 3 || n == 4) {
                boolean ok = true;
                for (int i = 1; i < n; i++) if (seq[i] - seq[i - 1] < GAP_MIN_NS) ok = false;
                if (ok && n == 3) triple();
                else if (ok) quad();
            }
            n = 0;
        }
    }

    private void triple() {
        triples++;
        lastTripleMs = System.currentTimeMillis();
        lastResult = "letar";
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                buzz(app, BUZZ_LETAR);
                if (!Gps.allowed(app)) {
                    lastResult = "ingen";
                    MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            buzz(app, BUZZ_INGET);
                        }
                    }, 1300);
                    return;
                }
                Gps.seek(app, SEEK_MS, new Runnable() {
                    @Override
                    public void run() {
                        lastResult = "hittat";
                        buzz(app, BUZZ_HITTAT);
                        WebActivity.poke();
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        lastResult = "ingen";
                        buzz(app, BUZZ_INGET);
                    }
                });
            }
        });
    }

    /** Fyra knackningar: Släppt nu. Skarpt bara medan loggningen går; när bara sidan lyssnar är det ett prov. */
    private void quad() {
        quads++;
        final long now = System.currentTimeMillis();
        lastQuadMs = now;
        final boolean skarpt;
        synchronized (Knock.class) {
            skarpt = users.contains("logg");
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (skarpt) {
                    try {
                        Store.pushEvent(app, new JSONObject().put("id", "slappt").put("t", now));
                    } catch (Exception ignored) {
                    }
                }
                buzz(app, BUZZ_SLAPPT);
                WebActivity.poke();
            }
        });
    }

    /** Vibrerar på full styrka, och stänger öronen så länge det pågår plus en halv sekund. */
    static void buzz(Context c, long[] pattern) {
        long total = 0;
        for (long p : pattern) total += p;
        muteUntil = SystemClock.elapsedRealtime() + total + 500;
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
            VibrationEffect fx;
            if (v.hasAmplitudeControl()) {
                int[] amp = new int[pattern.length];
                for (int i = 0; i < amp.length; i++) amp[i] = i % 2 == 1 ? 255 : 0;
                fx = VibrationEffect.createWaveform(pattern, amp, -1);
            } else {
                fx = VibrationEffect.createWaveform(pattern, -1);
            }
            v.vibrate(fx, aa);
        } catch (Exception ignored) {
        }
    }
}
