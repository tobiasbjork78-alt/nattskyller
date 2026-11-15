package se.seklof.vaken;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Vakten. Lyssnar på tre saker: skärmen släcks, skärmen tänds, telefonen låses upp.
 * Stegräknaren går i telefonens egen lågströmskrets och läses bara av vid de tillfällena.
 * Tjänsten håller inte telefonen vaken och gör inget mellan händelserna.
 */
public class WatchService extends Service implements SensorEventListener {
    static final String ACTION_CONFIRM = "se.seklof.vaken.CONFIRM";
    private static final String CHANNEL = "vakt";

    private Handler handler;
    private SensorManager sensors;
    private boolean stepsOn;
    private volatile float steps = -1f;
    private boolean receiving;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            final long now = System.currentTimeMillis();
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    flushThen(new Runnable() {
                        @Override
                        public void run() {
                            Brain.screenOff(WatchService.this, now, steps);
                        }
                    });
                    break;
                case Intent.ACTION_SCREEN_ON:
                    Brain.screenOn(WatchService.this, now);
                    // Telefon utan skärmlås ger ingen upplåsning: räkna då en minut med tänd skärm som att den används.
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                            if (pm != null && pm.isInteractive() && km != null && !km.isKeyguardLocked()) {
                                Brain.present(WatchService.this, now, steps);
                                MainActivity.poke();
                            }
                        }
                    }, 60_000L);
                    break;
                case Intent.ACTION_USER_PRESENT:
                    flushThen(new Runnable() {
                        @Override
                        public void run() {
                            Brain.present(WatchService.this, now, steps);
                            MainActivity.poke();
                        }
                    });
                    break;
                default:
                    break;
            }
        }
    };

    static void start(Context c) {
        Intent i = new Intent(c, WatchService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Vakten", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Vaken").setContentText("Ser när du vaknat och startar morgonbriefen.")
                .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        // Skärm av/på och upplåsning kommer från systemet och når fram även till en mottagare som inte är öppen utåt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
        receiving = true;

        startSteps();

        // Startar tjänsten medan skärmen redan är släckt (efter omstart): räkna därifrån.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) Brain.screenOff(this, System.currentTimeMillis(), steps);
        Brain.p(this).edit().putLong("serviceAt", System.currentTimeMillis()).apply();
    }

    private void startSteps() {
        if (stepsOn) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return;
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s == null) return;
        // Stegen samlas i telefonens lågströmskrets och lämnas över högst var femte minut, eller när vi ber om dem.
        stepsOn = sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 300_000_000);
    }

    /** Ber stegräknaren lämna över det den har, och kör sedan r. */
    private void flushThen(final Runnable r) {
        if (stepsOn && sensors != null) {
            try {
                sensors.flush(this);
            } catch (Exception ignored) {
            }
            handler.postDelayed(r, 1500L);
        } else {
            r.run();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startSteps();
        if (intent != null && ACTION_CONFIRM.equals(intent.getAction())) {
            flushThen(new Runnable() {
                @Override
                public void run() {
                    Brain.confirm(WatchService.this, System.currentTimeMillis(), steps);
                    MainActivity.poke();
                }
            });
        }
        if (!Brain.on(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER) steps = e.values[0];
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onDestroy() {
        if (receiving) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiving = false;
        }
        if (stepsOn && sensors != null) sensors.unregisterListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
