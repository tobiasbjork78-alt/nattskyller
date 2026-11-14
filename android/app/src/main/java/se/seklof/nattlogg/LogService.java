package se.seklof.nattlogg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Förgrundstjänst som ber om en wifi-sökning var 30:e sekund och sparar vilka sändare som hörs.
 * Laddar upp var tionde minut. Stänger av sig själv kl 05:00.
 */
public class LogService extends Service {
    private static final String CHANNEL = "logg";
    private static final long SCAN_MS = 30_000L;
    private static final long UPLOAD_MS = 10L * 60_000L;
    private static final long MIN_GAP_MS = 8_000L;
    private static final long STALE_US = 60L * 1_000_000L;
    private static final int GPS_EVERY_MIN = 5;   // går att ändra från sidan (Natt.gpsEvery), se regler.json
    private static final long GPS_LISTEN_MS = 25_000L;

    private Handler handler;
    private WifiManager wifi;
    private PowerManager.WakeLock wakeLock;
    private boolean receiving;
    private long lastRecord;
    private long stopAt;
    private long lastGps;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            record(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false));
        }
    };

    private final Runnable scanTick = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() >= stopAt) {
                stopSelf();
                return;
            }
            boolean ok = false;
            try {
                ok = wifi.startScan();
            } catch (Exception ignored) {
            }
            Store.prefs(LogService.this).edit().putBoolean("lastScanOk", ok).putLong("beat", System.currentTimeMillis()).apply();
            // Med jämna mellanrum (fem minuter om inget annat sagts): lyssna en kort stund efter GPS, så att "Var är vi?" har ett färskt läge att räkna från.
            long now = System.currentTimeMillis();
            long every = Math.max(2, Math.min(240, Store.prefs(LogService.this).getInt("gpsEveryMin", GPS_EVERY_MIN))) * 60_000L;
            if (now - lastGps >= every) {
                lastGps = now;
                Gps.burst(LogService.this, GPS_LISTEN_MS);
            }
            handler.postDelayed(this, SCAN_MS);
        }
    };

    private final Runnable uploadTick = new Runnable() {
        @Override
        public void run() {
            uploadAsync();
            handler.postDelayed(this, UPLOAD_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Loggning", NotificationManager.IMPORTANCE_LOW));
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, n);
        }

        if (!receiving) {
            IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }
            receiving = true;

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nattlogg:logg");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(13L * 3600_000L);

            stopAt = nextFiveAm();
            Store.cleanNights(this);
            Gps.clean(this);
            Store.prefs(this).edit().putBoolean("running", true).putLong("startedAt", System.currentTimeMillis())
                    .putLong("beat", System.currentTimeMillis()).apply();
            handler.post(scanTick);
            handler.postDelayed(uploadTick, UPLOAD_MS);
            Knock.want(this, "logg", true);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(scanTick);
        handler.removeCallbacks(uploadTick);
        if (receiving) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiving = false;
        }
        Knock.want(this, "logg", false);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        Store.prefs(this).edit().putBoolean("running", false).apply();
        uploadAsync();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void uploadAsync() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Store.upload(app);
                Store.uploadGps(app);
            }
        }).start();
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, WebActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Nattskyller loggar wifi")
                .setContentText("Stängs av själv kl 05:00")
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private static long nextFiveAm() {
        Calendar c = Calendar.getInstance();
        if (c.get(Calendar.HOUR_OF_DAY) >= 5) c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 5);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Sparar en rad: {"t":sekunder,"u":1,"a":[["b4:b2:e9:56:a2:da",-46,2472],...]} med starkaste först. */
    private void record(boolean updated) {
        long now = System.currentTimeMillis();
        if (now - lastRecord < MIN_GAP_MS) return;
        List<ScanResult> results;
        try {
            results = wifi.getScanResults();
        } catch (SecurityException e) {
            Store.prefs(this).edit().putString("lastErr", "Saknar platsbehörighet").apply();
            return;
        }
        if (results == null) return;

        long bootUs = SystemClock.elapsedRealtime() * 1000L;
        List<ScanResult> fresh = new ArrayList<>();
        for (ScanResult r : results) {
            if (r.BSSID != null && bootUs - r.timestamp < STALE_US) fresh.add(r);
        }
        if (fresh.isEmpty()) return;
        Collections.sort(fresh, (a, b) -> Integer.compare(b.level, a.level));

        StringBuilder sb = new StringBuilder(64 + fresh.size() * 34);
        sb.append("{\"t\":").append(now / 1000L).append(",\"u\":").append(updated ? 1 : 0).append(",\"a\":[");
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < fresh.size(); i++) {
            ScanResult r = fresh.get(i);
            if (i > 0) sb.append(',');
            sb.append("[\"").append(r.BSSID.toLowerCase()).append("\",").append(r.level).append(',').append(r.frequency).append(']');
            if (i < 3) {
                String b = r.BSSID.toLowerCase();
                if (i > 0) top.append("   ");
                top.append(b.length() > 9 ? b.substring(9) : b).append(' ').append(r.level);
            }
        }
        sb.append("]}");
        Store.append(this, sb.toString());
        lastRecord = now;

        SharedPreferences p = Store.prefs(this);
        String shift = Store.shiftDate(now);
        int count = shift.equals(p.getString("shift", "")) ? p.getInt("scans", 0) + 1 : 1;
        p.edit().putString("shift", shift).putInt("scans", count).putLong("lastScan", now)
                .putString("lastTop", top.toString()).putInt("lastCount", fresh.size()).apply();
    }
}
