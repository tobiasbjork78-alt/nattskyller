package se.seklof.nattlogg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

/** Tar emot larm som går och tryck på knapparna i aviseringarna. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final Context c = context.getApplicationContext();
        final String action = intent.getAction();
        Uri data = intent.getData();
        String id = data != null && data.getLastPathSegment() != null ? data.getLastPathSegment() : "";
        long now = System.currentTimeMillis();

        try {
            if (Alarms.ACTION_FIRE.equals(action)) {
                JSONObject a = Alarms.get(c, id);
                if (a == null) return;
                Alarms.advance(c, id);
                AlarmService.ring(c, id, a.optString("kind", "pizza"), a.optString("title", "Larm"), a.optString("text", ""));
            } else if (Alarms.ACTION_ACK.equals(action)) {
                AlarmService.stop(c);
                Store.pushEvent(c, new JSONObject().put("id", id).put("a", "ack").put("t", now));
                WebActivity.poke();
            } else if (Alarms.ACTION_SNOOZE.equals(action)) {
                long until = now + intent.getIntExtra("min", 5) * 60_000L;
                AlarmService.stop(c);
                Alarms.snooze(c, id, intent.getStringExtra("kind"), intent.getStringExtra("title"), intent.getStringExtra("text"), until);
                Store.pushEvent(c, new JSONObject().put("id", id).put("a", "snooze").put("until", until).put("t", now));
                WebActivity.poke();
            } else if (Alarms.ACTION_RUN.equals(action)) {
                // Klar / Paus / Fortsätt på momentet som går. Klockslaget för trycket är det som räknas.
                String what = intent.getStringExtra("a");
                JSONObject note = new JSONObject(Store.prefs(c).getString("runNote", "{}"));
                String key = note.optString("k", "");
                if (key.isEmpty() || what == null) return;
                Store.pushEvent(c, new JSONObject().put("id", "run").put("a", what).put("k", key).put("t", now));
                if ("klar".equals(what)) {
                    RunNote.clear(c);
                } else {
                    RunNote.show(c, key, note.optString("name", ""), "paus".equals(what));
                }
                WebActivity.poke();
            }
        } catch (Exception ignored) {
        }
    }
}
