package se.seklof.nattlogg;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Larmen (pizzalarm, tvättklocka) som riktiga Android-larm. De ringer även när skärmen är släckt
 * och appen inte är framme. Varje larm sparas i inställningarna så att de kan läggas tillbaka efter omstart.
 *
 * Ett larm: {"at":ms,"kind":"pizza"|"tvatt","title":..,"text":..,"next":ms eller 0}.
 * "next" är nästa ordinarie tid; när larmet har ringt flyttas det dit och "next" ett dygn framåt.
 */
final class Alarms {
    static final String ACTION_FIRE = "se.seklof.nattlogg.FIRE";
    static final String ACTION_ACK = "se.seklof.nattlogg.ACK";
    static final String ACTION_SNOOZE = "se.seklof.nattlogg.SNOOZE";
    static final String ACTION_RUN = "se.seklof.nattlogg.RUN";
    static final long DAY = 24L * 3600_000L;
    private static final Object LOCK = new Object();

    private Alarms() {}

    static JSONObject all(Context c) {
        try {
            return new JSONObject(Store.prefs(c).getString("alarms", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, JSONObject all) {
        Store.prefs(c).edit().putString("alarms", all.toString()).commit();
    }

    static JSONObject get(Context c, String id) {
        return all(c).optJSONObject(id);
    }

    static void set(Context c, String id, long at, String kind, String title, String text, long next) {
        synchronized (LOCK) {
            try {
                JSONObject all = all(c);
                JSONObject a = new JSONObject();
                a.put("at", at).put("kind", kind).put("title", title).put("text", text).put("next", next);
                all.put(id, a);
                save(c, all);
            } catch (Exception ignored) {
            }
        }
        schedule(c, id, at);
    }

    static void cancel(Context c, String id) {
        synchronized (LOCK) {
            JSONObject all = all(c);
            all.remove(id);
            save(c, all);
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(firePi(c, id));
    }

    /** Efter att ett larm har ringt: flytta till nästa ordinarie tid, eller ta bort engångslarm. */
    static void advance(Context c, String id) {
        long at = 0;
        synchronized (LOCK) {
            try {
                JSONObject all = all(c);
                JSONObject a = all.optJSONObject(id);
                if (a == null) return;
                long next = a.optLong("next", 0);
                if (next > 0) {
                    long now = System.currentTimeMillis();
                    while (next <= now) next += DAY;
                    a.put("at", next).put("next", next + DAY);
                    at = next;
                } else {
                    all.remove(id);
                }
                save(c, all);
            } catch (Exception ignored) {
            }
        }
        if (at > 0) schedule(c, id, at);
    }

    /** Snooze från aviseringen: ring igen om en stund, behåll nästa ordinarie tid. */
    static void snooze(Context c, String id, String kind, String title, String text, long until) {
        long next = 0;
        JSONObject a = get(c, id);
        if (a != null) next = a.optLong("at", 0);
        set(c, id, until, kind, title, text, next);
    }

    static PendingIntent firePi(Context c, String id) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_FIRE).setData(Uri.parse("natt://larm/" + id));
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean canExact(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    static void schedule(Context c, String id, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = firePi(c, id);
        long when = Math.max(at, System.currentTimeMillis() + 500);
        try {
            if (canExact(c)) {
                PendingIntent show = PendingIntent.getActivity(c, 1, new Intent(c, WebActivity.class), PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), pi);
                return;
            }
        } catch (SecurityException ignored) {
        }
        // Utan rätt att ställa exakta larm: så nära som systemet tillåter.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    /** Efter omstart av telefonen, eller när appen har uppdaterats: lägg tillbaka alla larm. */
    static void restoreAll(Context c) {
        JSONObject all = all(c);
        long now = System.currentTimeMillis();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String id = it.next();
            JSONObject a = all.optJSONObject(id);
            if (a == null) continue;
            long at = a.optLong("at", 0);
            if (at > now) {
                schedule(c, id, at);
            } else {
                advance(c, id);
            }
        }
    }
}
