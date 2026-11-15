package se.seklof.vaken;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Avgör när ägaren har sovit och vaknat.
 *
 * Sover   = skärmen har varit släckt och olåst i minst tre timmar, och stegräknaren har nästan inte rört sig.
 *           Stegen skiljer "telefonen låg på nattduksbordet" från "telefonen låg i fickan under ett arbetspass".
 * Vaknar  = första upplåsningen efter en sådan period.
 * Bekräfta = en stund senare kontrolleras att telefonen faktiskt har använts. Annars var det bara en titt på
 *           klockan mitt i sömnen: då fortsätter sömnen räknas från där den började.
 * Sedan   = briefen startas hos Claude så att den landar ungefär "delayMin" minuter efter uppvaknandet.
 *
 * Allt läge ligger i SharedPreferences, så att det överlever att tjänsten startas om.
 */
final class Brain {
    static final String PREFS = "vaken";
    static final String DEFAULT_URL = "https://api.anthropic.com/v1/claude_code/routines/trig_012XAJgdE6rxBFv4mcwJfek2/fire";
    static final int DEFAULT_DELAY_MIN = 15;
    static final int DEFAULT_MIN_SLEEP_MIN = 240;
    static final int DEFAULT_EARLIEST_H = 6;
    private static final int BUILD_MIN = 3;                  // så lång tid tar briefen ungefär att bygga
    private static final float MAX_STEPS_ASLEEP = 80f;
    private static final long MAX_SLEEP_MS = 12L * 3600_000L; // längre än så: telefonen var kvarglömd, inte en natt
    private static final long MIN_GAP_MS = 16L * 3600_000L;   // högst en brief per sexton timmar

    private Brain() {
    }

    static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean on(Context c) {
        return p(c).getBoolean("on", true);
    }

    static int delayMin(Context c) {
        return Math.max(1, Math.min(120, p(c).getInt("delayMin", DEFAULT_DELAY_MIN)));
    }

    static int minSleepMin(Context c) {
        return Math.max(30, Math.min(600, p(c).getInt("minSleepMin", DEFAULT_MIN_SLEEP_MIN)));
    }

    static int earliestH(Context c) {
        return Math.max(0, Math.min(23, p(c).getInt("earliestH", DEFAULT_EARLIEST_H)));
    }

    static String hm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    static String dur(long ms) {
        long min = Math.max(0, ms / 60_000L);
        return (min / 60) + " h " + (min % 60) + " min";
    }

    private static boolean interactive(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    // ---------- Händelser från vakten ----------

    static synchronized void screenOff(Context c, long now, float steps) {
        SharedPreferences s = p(c);
        SharedPreferences.Editor e = s.edit();
        long onSince = s.getLong("onSince", 0);
        if (s.getLong("wakeAt", 0) > 0 && onSince > 0) e.putLong("activeMs", s.getLong("activeMs", 0) + Math.max(0, now - onSince));
        e.putLong("onSince", 0);
        if (s.getLong("offAt", 0) == 0) e.putLong("offAt", now).putFloat("offSteps", steps);
        e.apply();
    }

    static synchronized void screenOn(Context c, long now) {
        p(c).edit().putLong("onSince", now).apply();
    }

    /** Telefonen låstes upp (eller användes en stund utan lås). */
    static synchronized void present(Context c, long now, float steps) {
        SharedPreferences s = p(c);
        // Ett larm som aldrig kom (omstart mitt i väntan) får inte låsa vakten.
        if (s.getLong("wakeAt", 0) != 0 && now - s.getLong("wakeAt", 0) > 2 * 3600_000L) {
            s.edit().putLong("wakeAt", 0).apply();
        }
        long offAt = s.getLong("offAt", 0);
        if (offAt == 0) return;
        SharedPreferences.Editor e = s.edit().putLong("offAt", 0);
        long slept = now - offAt;
        float offSteps = s.getFloat("offSteps", -1f);
        float walked = steps >= 0 && offSteps >= 0 && steps >= offSteps ? steps - offSteps : 0f;
        String no = null;
        if (!on(c)) no = "avstängd";
        else if (s.getLong("wakeAt", 0) != 0) no = "väntar redan på en brief";
        else if (slept < minSleepMin(c) * 60_000L) no = "för kort för att vara en natt";
        else if (slept > MAX_SLEEP_MS) no = "för länge, räknas som kvarglömd";
        else if (walked > MAX_STEPS_ASLEEP) no = "för många steg, låg nog i fickan";
        else if (hourOf(now) < earliestH(c)) no = "före kl " + earliestH(c);
        else if (now - s.getLong("lastFire", 0) < MIN_GAP_MS) no = "en brief har redan gått i dag";
        if (slept >= 60 * 60_000L) {
            note(c, e, s, hm(now) + ": orörd " + dur(slept) + ", " + Math.round(walked) + " steg. "
                    + (no == null ? "Räknas som att du vaknat." : "Räknas inte: " + no + "."));
        }
        if (no == null) {
            e.putLong("wakeAt", now).putLong("sleepFrom", offAt).putFloat("sleepSteps", offSteps)
                    .putFloat("wakeSteps", steps).putLong("activeMs", 0).putLong("onSince", now);
            e.apply();
            long at = now + Math.max(1, delayMin(c) - BUILD_MIN) * 60_000L;
            schedule(c, at);
            return;
        }
        e.apply();
    }

    private static int hourOf(long ms) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ms);
        return cal.get(java.util.Calendar.HOUR_OF_DAY);
    }

    /** Sparar de senaste iakttagelserna, så att det går att se varför en natt räknades eller inte. */
    private static void note(Context c, SharedPreferences.Editor e, SharedPreferences s, String line) {
        try {
            JSONArray old = new JSONArray(s.getString("notes", "[]"));
            JSONArray arr = new JSONArray();
            arr.put(line);
            for (int i = 0; i < old.length() && i < 7; i++) arr.put(old.get(i));
            e.putString("notes", arr.toString());
        } catch (Exception ignored) {
        }
    }

    /** Larmet en stund efter uppvaknandet: var det på riktigt? */
    static synchronized void confirm(Context c, long now, float steps) {
        SharedPreferences s = p(c);
        long wakeAt = s.getLong("wakeAt", 0);
        if (wakeAt == 0) return;
        long sleepFrom = s.getLong("sleepFrom", 0);
        long onSince = s.getLong("onSince", 0);
        boolean inter = interactive(c);
        long active = s.getLong("activeMs", 0) + (inter && onSince > 0 ? Math.max(0, now - onSince) : 0);
        float wakeSteps = s.getFloat("wakeSteps", -1f);
        float walked = steps >= 0 && wakeSteps >= 0 && steps >= wakeSteps ? steps - wakeSteps : 0f;
        boolean awake = inter || active >= 120_000L || walked >= 25f;
        SharedPreferences.Editor e = s.edit().putLong("wakeAt", 0);
        if (!awake) {
            // Bara en titt på klockan. Sömnen fortsätter räknas från där den började.
            note(c, e, s, hm(now) + ": telefonen lades ifrån sig igen efter kl " + hm(wakeAt) + ". Ingen brief, natten fortsätter.");
            e.putLong("offAt", sleepFrom).putFloat("offSteps", s.getFloat("sleepSteps", -1f)).apply();
            return;
        }
        e.putLong("lastFire", now).apply();
        addLog(c, sleepFrom, wakeAt);
        String text = "Tobbe vaknade kl " + hm(wakeAt) + ". Telefonen hade legat still och låst i " + dur(wakeAt - sleepFrom)
                + ", sedan kl " + hm(sleepFrom) + ". Det här skickas " + ((now - wakeAt) / 60_000L) + " min efter uppvaknandet.";
        Fire.send(c, text, false);
    }

    private static void addLog(Context c, long from, long to) {
        try {
            JSONArray old = new JSONArray(p(c).getString("log", "[]"));
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("from", from).put("to", to));
            for (int i = 0; i < old.length() && i < 13; i++) arr.put(old.get(i));
            p(c).edit().putString("log", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static void schedule(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c.getApplicationContext(), 1,
                new Intent(c.getApplicationContext(), TickReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return;
            }
        } catch (Exception ignored) {
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** En rad om läget, till skärmen i appen. */
    static String status(Context c) {
        SharedPreferences s = p(c);
        if (!on(c)) return "Avstängd.";
        long wakeAt = s.getLong("wakeAt", 0), offAt = s.getLong("offAt", 0), now = System.currentTimeMillis();
        if (wakeAt > 0) return "Du verkar ha vaknat kl " + hm(wakeAt) + ". Briefen startas strax.";
        if (offAt > 0 && now - offAt >= minSleepMin(c) * 60_000L) return "Telefonen har legat orörd sedan kl " + hm(offAt) + ".";
        return "Vaktar. Briefen startas " + delayMin(c) + " min efter att du vaknat.";
    }
}
