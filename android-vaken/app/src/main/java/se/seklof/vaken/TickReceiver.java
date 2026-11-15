package se.seklof.vaken;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Larmet en stund efter uppvaknandet. Lämnar över till vakten, som läser av stegen och avgör. */
public class TickReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            Intent i = new Intent(c, WatchService.class).setAction(WatchService.ACTION_CONFIRM);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            // Vakten gick inte att nå: avgör utan stegräknaren.
            Brain.confirm(c, System.currentTimeMillis(), -1f);
        }
    }
}
