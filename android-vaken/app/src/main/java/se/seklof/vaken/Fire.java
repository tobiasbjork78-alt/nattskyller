package se.seklof.vaken;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Startar morgonbriefen hos Claude: ett anrop till uppdragets egen adress, med uppdragets egen nyckel.
 * Nyckeln kan bara starta just det uppdraget. Den ligger bara i telefonen och klistras in av ägaren.
 */
final class Fire {
    private Fire() {
    }

    static void send(final Context c, final String text, final boolean test) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String res = "";
                for (int i = 0; i < 4; i++) {
                    res = once(app, text);
                    if (res.startsWith("ok") || res.startsWith("fel 4")) break;   // 4xx blir inte bättre av att försöka igen
                    try {
                        Thread.sleep(60_000L);
                    } catch (InterruptedException ignored) {
                    }
                }
                String line = Brain.hm(System.currentTimeMillis()) + (test ? " prov: " : " brief: ") + res;
                Brain.p(app).edit().putString("lastStatus", line).apply();
                if (!res.startsWith("ok")) note(app, "Briefen kunde inte startas", res);
                MainActivity.poke();
            }
        }, "vaken-fire").start();
    }

    private static String once(Context c, String text) {
        String url = Brain.p(c).getString("url", Brain.DEFAULT_URL).trim();
        String token = Brain.p(c).getString("token", "").trim();
        if (token.isEmpty()) return "ingen nyckel inlagd";
        if (!url.startsWith("https://api.anthropic.com/")) return "adressen ser fel ut";
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(20_000);
            con.setReadTimeout(40_000);
            con.setDoOutput(true);
            con.setRequestProperty("Authorization", "Bearer " + token);
            con.setRequestProperty("anthropic-version", "2023-06-01");
            con.setRequestProperty("anthropic-beta", "experimental-cc-routine-2026-04-01");
            con.setRequestProperty("Content-Type", "application/json");
            byte[] body = new JSONObject().put("text", text).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = con.getOutputStream()) {
                os.write(body);
            }
            int code = con.getResponseCode();
            if (code == 200) return "ok, Claude bygger briefen";
            String why = "";
            try (InputStream in = con.getErrorStream()) {
                if (in != null) {
                    byte[] buf = new byte[600];
                    int n = in.read(buf);
                    if (n > 0) why = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8)).optJSONObject("error").optString("message", "");
                }
            } catch (Exception ignored) {
            }
            if (code == 401) return "fel 401: nyckeln stämmer inte med uppdraget";
            if (code == 429) return "fel 429: för många starter den här timmen";
            return "fel " + code + (why.isEmpty() ? "" : ": " + why);
        } catch (Exception e) {
            return "inget nät (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static void note(Context c, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel("fel", "Fel", NotificationManager.IMPORTANCE_DEFAULT));
            }
            PendingIntent pi = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, "fel").setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).build();
            nm.notify(3, n);
        } catch (Exception ignored) {
        }
    }
}
