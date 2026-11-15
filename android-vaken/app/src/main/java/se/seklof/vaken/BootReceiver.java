package se.seklof.vaken;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startar vakten igen efter att telefonen startats om eller appen uppdaterats. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Brain.on(c)) return;
        try {
            WatchService.start(c);
        } catch (Exception ignored) {
        }
    }
}
