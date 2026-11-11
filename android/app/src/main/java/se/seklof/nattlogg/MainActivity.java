package se.seklof.nattlogg;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** En enda skärm: läge, starta/stoppa, skicka nu och inställning av arkiv och nyckel. */
public class MainActivity extends Activity {
    private static final int REQ = 7;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button toggle;
    private EditText repo;
    private EditText token;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 2000);
        }
    };

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setMinHeight(dp(56));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences p = Store.prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(40));

        root.addView(label("Nattlogg", 28, 0xFFE8EDF1));
        TextView sub = label("Loggar vilka wifi-sändare telefonen hör, så att Claude kan räkna ut vilket kök du är i.", 14, 0xFF8A9BA8);
        sub.setPadding(0, 0, 0, 0);
        root.addView(sub);

        status = label("", 16, 0xFFE8EDF1);
        status.setLineSpacing(dp(4), 1f);
        root.addView(status);

        toggle = button("Starta loggning");
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Store.prefs(MainActivity.this).getBoolean("running", false)) {
                    stopService(new Intent(MainActivity.this, LogService.class));
                    toast("Loggningen stoppad");
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            render();
                        }
                    }, 400);
                } else {
                    startLogging();
                }
            }
        });
        root.addView(toggle);

        Button send = button("Skicka nu");
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("Skickar …");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean ok = Store.upload(getApplicationContext());
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast(ok ? "Skickat" : Store.prefs(MainActivity.this).getString("lastErr", "Gick inte att skicka"));
                                render();
                            }
                        });
                    }
                }).start();
            }
        });
        root.addView(send);

        root.addView(label("GitHub-arkiv (konto/namn)", 13, 0xFF8A9BA8));
        repo = new EditText(this);
        repo.setSingleLine(true);
        repo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        repo.setText(p.getString("repo", Store.DEFAULT_REPO));
        root.addView(repo);

        root.addView(label("Nyckel (börjar med github_pat_)", 13, 0xFF8A9BA8));
        token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint(p.getString("token", "").isEmpty() ? "Klistra in nyckeln här" : "Sparad – lämna tomt för att behålla");
        root.addView(token);

        Button save = button("Spara och testa");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndTest();
            }
        });
        root.addView(save);

        root.addView(label("Nyckeln sparas bara i den här telefonen. Loggningen stänger av sig själv kl 05:00.", 13, 0xFF8A9BA8));

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(0xFF0E1419);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void saveAndTest() {
        String r = repo.getText().toString().trim()
                .replaceFirst("(?i)^https?://github\\.com/", "").replaceFirst("(?i)\\.git$", "");
        String t = token.getText().toString().replaceAll("\\s+", "");
        SharedPreferences p = Store.prefs(this);
        if (t.isEmpty()) t = p.getString("token", "");
        if (!r.matches("[^/\\s]+/[^/\\s]+")) {
            toast("Arkivet ska skrivas som konto/namn");
            return;
        }
        if (t.isEmpty()) {
            toast("Klistra in nyckeln först");
            return;
        }
        p.edit().putString("repo", r).putString("token", t).apply();
        token.setText("");
        token.setHint("Sparad – lämna tomt för att behålla");
        toast("Testar …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int code = Store.test(getApplicationContext());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(code == 200 ? "Kopplingen fungerar" : Store.errText(code));
                    }
                });
            }
        }).start();
    }

    private void startLogging() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ);
            return;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isLocationEnabled()) {
                toast("Slå på Plats i telefonens snabbmeny – annars får appen inte läsa av wifi");
                return;
            }
        }
        startForegroundService(new Intent(this, LogService.class));
        toast("Loggningen är igång");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                render();
            }
        }, 400);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLogging();
        } else {
            toast("Utan platsbehörighet (exakt plats) får appen inte läsa av wifi-sändare");
        }
    }

    private void render() {
        SharedPreferences p = Store.prefs(this);
        boolean running = p.getBoolean("running", false);
        long now = System.currentTimeMillis();
        StringBuilder s = new StringBuilder();
        s.append(running ? "● Loggar" : "○ Avstängd");
        boolean tonight = Store.shiftDate(now).equals(p.getString("shift", ""));
        long last = p.getLong("lastScan", 0);
        if (tonight && last > 0) {
            s.append("\n").append(p.getInt("scans", 0)).append(" avläsningar i natt, senast ").append(Store.clock(last))
                    .append(" (").append((now - last) / 1000).append(" s sedan)");
            s.append("\n").append(p.getInt("lastCount", 0)).append(" sändare. Starkast: ").append(p.getString("lastTop", ""));
        } else if (running) {
            s.append("\nVäntar på första avläsningen …");
        }
        long pend = Store.pendingBytes(this);
        long up = p.getLong("lastUpload", 0);
        s.append("\nVäntar på att skickas: ").append(pend < 1024 ? pend + " byte" : (pend / 1024) + " kB");
        if (up > 0) s.append("\nSenast skickat ").append(Store.shiftDate(up).equals(Store.shiftDate(now)) ? Store.clock(up) : Store.shiftDate(up));
        String err = p.getString("lastErr", "");
        if (!err.isEmpty()) s.append("\n⚠ ").append(err);
        if (p.getString("token", "").isEmpty()) s.append("\n⚠ Ingen nyckel inlagd – loggen stannar i telefonen");
        status.setText(s.toString());
        toggle.setText(running ? "Stoppa loggning" : "Starta loggning");
    }
}
