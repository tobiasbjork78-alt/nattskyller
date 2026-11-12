package se.seklof.nattlogg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Spelar larmljudet i slinga tills det stängs av, även med släckt skärm.
 * Ljudet går på larmvolymen, så det hörs även när telefonen står på ljudlös för samtal och aviseringar.
 * Pizzalarmet ringer i högst tio minuter, tvättklockan i högst fem.
 */
public class AlarmService extends Service {
    private static final String CHANNEL = "larm";
    private static final String CHANNEL_MISSED = "larm_missat";
    private static final int ID = 2;
    private static final int ID_MISSED = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private String ringId = "";
    private String ringTitle = "";

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            missed();
            stopSelf();
        }
    };

    static void ring(Context c, String id, String kind, String title, String text) {
        Intent i = new Intent(c, AlarmService.class).putExtra("id", id).putExtra("kind", kind)
                .putExtra("title", title).putExtra("text", text);
        try {
            c.startForegroundService(i);
        } catch (Exception e) {
            // Fick tjänsten inte starta: visa åtminstone en avisering med systemets larmljud.
            fallback(c, title, text);
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, AlarmService.class));
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_MISSED);
    }

    static String ringing(Context c) {
        return Store.prefs(c).getString("ringing", "");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String id = intent.getStringExtra("id");
        String kind = intent.getStringExtra("kind");
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        if (id == null) id = "";
        if (kind == null) kind = "pizza";
        if (title == null) title = "Larm";
        if (text == null) text = "";
        boolean pizza = !"tvatt".equals(kind);
        ringId = id;
        ringTitle = title;

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Larm", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
        nm.cancel(ID_MISSED);

        Notification n = build(id, kind, title, text, pizza);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(ID, n);
            }
        } catch (Exception e) {
            fallback(this, title, text);
            stopSelf();
            return START_NOT_STICKY;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (wakeLock == null && pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nattlogg:larm");
            wakeLock.setReferenceCounted(false);
        }
        if (wakeLock != null) wakeLock.acquire(11L * 60_000L);

        startSound(pizza ? R.raw.pizza : R.raw.tvatt);
        startVibration(pizza);
        Store.prefs(this).edit().putString("ringing", id).commit();
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, (pizza ? 10L : 5L) * 60_000L);
        WebActivity.poke();
        return START_NOT_STICKY;
    }

    private PendingIntent broadcast(String action, String id, int req, Intent extras) {
        Intent i = new Intent(this, AlarmReceiver.class).setAction(action).setData(Uri.parse("natt://larm/" + id));
        if (extras != null) i.putExtras(extras);
        return PendingIntent.getBroadcast(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification build(String id, String kind, String title, String text, boolean pizza) {
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, WebActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open);
        b.addAction(new Notification.Action.Builder(null, pizza ? "Stäng av" : "Tysta", broadcast(Alarms.ACTION_ACK, id, 21, null)).build());
        if (pizza) {
            Intent base = new Intent().putExtra("kind", kind).putExtra("title", title).putExtra("text", text);
            b.addAction(new Notification.Action.Builder(null, "Snooza 5 min",
                    broadcast(Alarms.ACTION_SNOOZE, id, 22, new Intent(base).putExtra("min", 5))).build());
            b.addAction(new Notification.Action.Builder(null, "Snooza 10 min",
                    broadcast(Alarms.ACTION_SNOOZE, id, 23, new Intent(base).putExtra("min", 10))).build());
        }
        return b.build();
    }

    private void startSound(int res) {
        stopSound();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            AssetFileDescriptor afd = getResources().openRawResourceFd(res);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            player = null;
        }
    }

    private void stopSound() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void startVibration(boolean pizza) {
        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
            if (pizza) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 200, 500, 700}, 0), aa);
            } else {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 150, 250, 7350}, 0), aa);
            }
        } catch (Exception ignored) {
        }
    }

    /** Ingen stängde av larmet: sluta låta och lämna en vanlig avisering kvar. */
    private void missed() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_MISSED, "Missade larm", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, WebActivity.class), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(ID_MISSED, new Notification.Builder(this, CHANNEL_MISSED)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(ringTitle)
                    .setContentText("Ringde " + Store.clock(System.currentTimeMillis() - 1).substring(0, 5) + " utan att stängas av")
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build());
        } catch (Exception ignored) {
        }
    }

    /** Reservväg om ljudtjänsten inte får starta: en avisering med systemets eget larmljud. */
    private static void fallback(Context c, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("larm_reserv", "Larm (reserv)", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
            PendingIntent open = PendingIntent.getActivity(c, 3, new Intent(c, WebActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, "larm_reserv")
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build();
            n.flags |= Notification.FLAG_INSISTENT;
            nm.notify(ID_MISSED, n);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        stopSound();
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Exception ignored) {
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        Store.prefs(this).edit().putString("ringing", "").commit();
        WebActivity.poke();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
