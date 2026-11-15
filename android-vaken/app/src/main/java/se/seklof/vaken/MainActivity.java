package se.seklof.vaken;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Appens enda skärm: läge, inställningar, nyckeln till uppdraget och de senaste nätterna. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF101826, CARD = 0xFF1A2436, LINE = 0xFF2A3650;
    private static final int TEXT = 0xFFE8EEF7, MUTED = 0xFF8FA0B8, ACCENT = 0xFFFFB347, GOOD = 0xFF7BD88F, WARN = 0xFFFF8A7A;
    private static final int REQ = 7;
    private static WeakReference<MainActivity> current = new WeakReference<>(null);

    private TextView status, last, delay, sleep, earliest, keyState, perms, nights, notes;
    private Button toggle;
    private EditText key, url;

    /** Ber skärmen rita om, om den är framme. Anropas från vakten. */
    static void poke() {
        final MainActivity a = current.get();
        if (a != null) a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                a.render();
            }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = col();
        root.setPadding(dp(18), dp(28), dp(18), dp(40));
        scroll.addView(root);

        TextView title = text("Vaken", 30, TEXT, true);
        root.addView(title);
        root.addView(text("Ser när du har sovit och vaknat, och ber då Claude bygga morgonbriefen.", 14, MUTED, false));

        // Läge
        LinearLayout c1 = card(root);
        status = text("", 17, TEXT, true);
        c1.addView(status);
        last = text("", 13, MUTED, false);
        last.setPadding(0, dp(6), 0, dp(10));
        c1.addView(last);
        LinearLayout r1 = row();
        toggle = button("", false);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !Brain.on(MainActivity.this);
                Brain.p(MainActivity.this).edit().putBoolean("on", on).apply();
                if (on) startWatch();
                else stopService(new Intent(MainActivity.this, WatchService.class));
                render();
            }
        });
        Button test = button("Brief nu", true);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Brain.p(MainActivity.this).getString("token", "").isEmpty()) {
                    toast("Lägg in nyckeln först.");
                    return;
                }
                toast("Startar briefen …");
                Fire.send(MainActivity.this, "Startad för hand i appen Vaken kl " + Brain.hm(System.currentTimeMillis())
                        + ". Tobbe är vaken och vill ha briefen nu.", true);
            }
        });
        r1.addView(toggle, weight());
        r1.addView(space(10));
        r1.addView(test, weight());
        c1.addView(r1);

        // Inställningar
        LinearLayout c2 = card(root);
        c2.addView(text("Inställningar", 13, MUTED, true));
        delay = stepper(c2, "Briefen kommer efter", new Step() {
            @Override
            public void by(int d) {
                set("delayMin", clamp(Brain.delayMin(MainActivity.this) + 5 * d, 5, 60));
            }
        });
        sleep = stepper(c2, "Räknas som natt efter", new Step() {
            @Override
            public void by(int d) {
                set("minSleepMin", clamp(Brain.minSleepMin(MainActivity.this) + 30 * d, 120, 480));
            }
        });
        earliest = stepper(c2, "Ingen brief före", new Step() {
            @Override
            public void by(int d) {
                set("earliestH", clamp(Brain.earliestH(MainActivity.this) + d, 0, 15));
            }
        });

        // Nyckeln
        LinearLayout c3 = card(root);
        c3.addView(text("Koppling till Claude", 13, MUTED, true));
        keyState = text("", 15, TEXT, false);
        keyState.setPadding(0, dp(8), 0, dp(8));
        c3.addView(keyState);
        key = field("Klistra in nyckeln (sk-ant-oat01-…)", true);
        c3.addView(key);
        url = field("Uppdragets adress", false);
        url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        c3.addView(url);
        Button save = button("Spara", true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences.Editor e = Brain.p(MainActivity.this).edit();
                String k = key.getText().toString().trim();
                String u = url.getText().toString().trim();
                if (!k.isEmpty()) e.putString("token", k);
                if (u.startsWith("https://api.anthropic.com/")) e.putString("url", u);
                else if (!u.isEmpty()) toast("Adressen ska börja med https://api.anthropic.com/");
                e.apply();
                key.setText("");
                render();
                toast("Sparat.");
            }
        });
        c3.addView(save);
        c3.addView(text("Nyckeln kan bara starta det här enda uppdraget. Den sparas bara i telefonen.", 12, MUTED, false));

        // Behörigheter
        LinearLayout c4 = card(root);
        c4.addView(text("Behörigheter", 13, MUTED, true));
        perms = text("", 14, TEXT, false);
        perms.setPadding(0, dp(8), 0, dp(10));
        perms.setLineSpacing(dp(4), 1f);
        c4.addView(perms);
        LinearLayout r4 = row();
        Button ask = button("Ge behörigheter", false);
        ask.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askPerms(true);
            }
        });
        Button batt = button("Batteri", false);
        batt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askBattery();
            }
        });
        r4.addView(ask, weight());
        r4.addView(space(10));
        r4.addView(batt, weight());
        c4.addView(r4);

        // Nätter
        LinearLayout c5 = card(root);
        c5.addView(text("Senaste nätterna", 13, MUTED, true));
        nights = text("", 14, TEXT, false);
        nights.setPadding(0, dp(8), 0, 0);
        nights.setLineSpacing(dp(4), 1f);
        c5.addView(nights);

        LinearLayout c6 = card(root);
        c6.addView(text("Vad vakten har sett", 13, MUTED, true));
        notes = text("", 12, MUTED, false);
        notes.setPadding(0, dp(8), 0, 0);
        notes.setLineSpacing(dp(4), 1f);
        c6.addView(notes);

        TextView ver = text("Vaken " + version(), 11, MUTED, false);
        ver.setPadding(0, dp(18), 0, 0);
        ver.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(ver);

        setContentView(scroll);
        url.setText(Brain.p(this).getString("url", Brain.DEFAULT_URL));
        if (!Brain.p(this).getBoolean("asked", false)) {
            Brain.p(this).edit().putBoolean("asked", true).apply();
            askPerms(false);
        }
        if (Brain.on(this)) startWatch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = new WeakReference<>(this);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        current = new WeakReference<>(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (Brain.on(this)) startWatch();   // så att vakten börjar läsa stegen
        render();
    }

    private void startWatch() {
        try {
            WatchService.start(this);
        } catch (Exception e) {
            toast("Vakten gick inte att starta.");
        }
    }

    private void render() {
        SharedPreferences s = Brain.p(this);
        boolean on = Brain.on(this);
        status.setText(Brain.status(this));
        status.setTextColor(on ? TEXT : WARN);
        String ls = s.getString("lastStatus", "");
        last.setText(ls.isEmpty() ? "Ingen brief har startats härifrån än." : "Senast " + ls);
        toggle.setText(on ? "Stäng av" : "Slå på");
        delay.setText(Brain.delayMin(this) + " min");
        int m = Brain.minSleepMin(this);
        sleep.setText((m / 60) + " h" + (m % 60 == 0 ? "" : " " + (m % 60) + " min") + " orörd");
        earliest.setText("kl " + (Brain.earliestH(this) < 10 ? "0" : "") + Brain.earliestH(this) + ":00");

        String t = s.getString("token", "");
        if (t.isEmpty()) {
            keyState.setText("Ingen nyckel inlagd än.");
            keyState.setTextColor(WARN);
        } else {
            keyState.setText("Nyckel inlagd (slutar på …" + t.substring(Math.max(0, t.length() - 4)) + ").");
            keyState.setTextColor(GOOD);
        }

        StringBuilder p = new StringBuilder();
        p.append(mark(granted(Manifest.permission.POST_NOTIFICATIONS, 33))).append(" Aviseringar\n");
        p.append(mark(granted(Manifest.permission.ACTIVITY_RECOGNITION, 29))).append(" Fysisk aktivitet (stegräknaren)\n");
        p.append(mark(batteryOk())).append(" Får gå i bakgrunden utan batterisparande");
        perms.setText(p.toString());

        StringBuilder n = new StringBuilder();
        try {
            JSONArray arr = new JSONArray(s.getString("log", "[]"));
            SimpleDateFormat day = new SimpleDateFormat("EEE d MMM", new Locale("sv", "SE"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long from = o.getLong("from"), to = o.getLong("to");
                if (n.length() > 0) n.append("\n");
                n.append(day.format(new Date(to))).append(": ").append(Brain.hm(from)).append("–").append(Brain.hm(to))
                        .append("  (").append(Brain.dur(to - from)).append(")");
            }
        } catch (Exception ignored) {
        }
        nights.setText(n.length() == 0 ? "Inga nätter än. Den första kommer efter att du sovit med appen igång." : n.toString());

        StringBuilder o = new StringBuilder();
        try {
            JSONArray arr = new JSONArray(s.getString("notes", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                if (o.length() > 0) o.append("\n");
                o.append(arr.getString(i));
            }
        } catch (Exception ignored) {
        }
        notes.setText(o.length() == 0 ? "Inget än." : o.toString());
    }

    // ---------- Behörigheter ----------

    private boolean granted(String perm, int fromSdk) {
        return Build.VERSION.SDK_INT < fromSdk || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryOk() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void askPerms(boolean openSettingsIfStuck) {
        List<String> need = new ArrayList<>();
        if (!granted(Manifest.permission.POST_NOTIFICATIONS, 33)) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!granted(Manifest.permission.ACTIVITY_RECOGNITION, 29)) need.add(Manifest.permission.ACTIVITY_RECOGNITION);
        if (need.isEmpty()) {
            if (openSettingsIfStuck) toast("Allt är redan beviljat.");
            return;
        }
        boolean stuck = false;
        for (String n : need) if (Brain.p(this).getBoolean("asked:" + n, false) && !shouldShowRequestPermissionRationale(n)) stuck = true;
        if (stuck && openSettingsIfStuck) {
            // Android frågar inte igen efter två nej: öppna appens sida i inställningarna i stället.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            return;
        }
        SharedPreferences.Editor e = Brain.p(this).edit();
        for (String n : need) e.putBoolean("asked:" + n, true);
        e.apply();
        requestPermissions(need.toArray(new String[0]), REQ);
    }

    private void askBattery() {
        if (batteryOk()) {
            toast("Redan klart.");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast("Öppna batteriinställningarna för appen för hand.");
            }
        }
    }

    // ---------- Små byggklossar ----------

    private interface Step {
        void by(int d);
    }

    private void set(String k, int v) {
        Brain.p(this).edit().putInt(k, v).apply();
        render();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private String mark(boolean ok) {
        return ok ? "✓" : "✗";
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View space(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1));
        return v;
    }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout l = col();
        l.setBackground(shape(CARD, LINE, 18));
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        parent.addView(l, lp);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? BG : TEXT);
        b.setBackground(shape(primary ? ACCENT : Color.TRANSPARENT, primary ? 0 : LINE, 14));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        return b;
    }

    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        e.setBackground(shape(BG, LINE, 12));
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        e.setLayoutParams(lp);
        return e;
    }

    /** En rad med etikett, värde och knapparna minus och plus. Ger tillbaka värdets textruta. */
    private TextView stepper(LinearLayout parent, String label, final Step step) {
        LinearLayout r = row();
        r.setPadding(0, dp(12), 0, 0);
        LinearLayout left = col();
        left.addView(text(label, 13, MUTED, false));
        TextView value = text("", 17, TEXT, true);
        left.addView(value);
        r.addView(left, weight());
        Button minus = button("−", false), plus = button("+", false);
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step.by(-1);
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step.by(1);
            }
        });
        r.addView(minus, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(space(8));
        r.addView(plus, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(r);
        return value;
    }
}
