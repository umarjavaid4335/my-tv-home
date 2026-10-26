package dev.mytvhome.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Opens My Home when the TV finishes a cold boot. Standby/Quick Start resumes the last app on its own;
 * a full power-on lands on the stock launcher, and this firmware does not restart the accessibility
 * helper after boot, so the helper can't fix that case.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Log.i("MyHome", "boot: " + i.getAction() + " -> My Home");
        try {
            c.startActivity(new Intent(c, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception e) {
            Log.w("MyHome", "boot start failed", e);
        }
    }
}
