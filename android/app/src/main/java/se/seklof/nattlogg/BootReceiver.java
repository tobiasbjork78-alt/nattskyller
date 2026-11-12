package se.seklof.nattlogg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Lägger tillbaka larmen när telefonen har startat om eller appen har uppdaterats. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            // Loggningen överlever varken omstart eller uppdatering: nollställ flaggan så att den kan startas igen.
            Store.prefs(context).edit().putBoolean("running", false).apply();
            Alarms.restoreAll(context.getApplicationContext());
        }
    }
}
