package se.seklof.nattlogg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

/** Den fasta aviseringen för momentet som går, med Klar och Paus som fungerar från låsskärmen. */
final class RunNote {
    private static final String CHANNEL = "pagar";
    private static final int ID = 3;

    private RunNote() {}

    private static PendingIntent action(Context c, String what, int req) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(Alarms.ACTION_RUN)
                .setData(Uri.parse("natt://run/" + what)).putExtra("a", what);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void show(Context c, String key, String name, boolean paused) {
        try {
            Store.prefs(c).edit().putString("runNote",
                    new JSONObject().put("k", key).put("name", name).put("paused", paused).toString()).commit();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Moment som pågår", NotificationManager.IMPORTANCE_LOW);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
            PendingIntent open = PendingIntent.getActivity(c, 2, new Intent(c, WebActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle((paused ? "Pausad: " : "Pågår: ") + name)
                    .setContentText(paused ? "Tryck Fortsätt eller Klar" : "Tryck Klar när du är färdig")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(open);
            if (paused) {
                b.addAction(new Notification.Action.Builder(null, "Fortsätt", action(c, "fortsatt", 11)).build());
                b.addAction(new Notification.Action.Builder(null, "Klar", action(c, "klar", 12)).build());
            } else {
                b.addAction(new Notification.Action.Builder(null, "Klar", action(c, "klar", 12)).build());
                b.addAction(new Notification.Action.Builder(null, "Paus", action(c, "paus", 13)).build());
            }
            nm.notify(ID, b.build());
        } catch (Exception ignored) {
        }
    }

    static void clear(Context c) {
        Store.prefs(c).edit().putString("runNote", "{}").commit();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID);
    }
}
