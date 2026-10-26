package dev.mytvhome.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional "Home button helper". On this TCL Android TV the Home key and the HOME intent always open the
 * stock launcher, even when My Home holds the HOME role. When enabled by the owner (Settings >
 * Accessibility), this service switches to My Home whenever the stock launcher comes to the front.
 *
 * Scope is deliberately minimal (res/xml/home_helper.xml): only TYPE_WINDOW_STATE_CHANGED events, only
 * from com.google.android.tvlauncher, no window-content access. Nothing is stored or sent anywhere.
 * Turning the service off restores stock behaviour completely.
 */
public class HomeHelperService extends AccessibilityService {
    static final String STOCK = "com.google.android.tvlauncher";
    private static final String PREF_ALLOW = "allowStockUntil";
    private static final long ALLOW_MS = 10 * 60 * 1000L;
    private long lastRedirect;

    /** Called by My Home's "Android TV Home" button: the stock launcher may stay for up to 10 minutes. */
    static void allowStockOnce(Context c) {
        prefs(c).edit().putLong(PREF_ALLOW, System.currentTimeMillis() + ALLOW_MS).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("helper", Context.MODE_PRIVATE);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!STOCK.contentEquals(String.valueOf(e.getPackageName()))) return;
        SharedPreferences p = prefs(this);
        if (System.currentTimeMillis() < p.getLong(PREF_ALLOW, 0)) return;   // owner chose the stock home on purpose
        long now = SystemClock.elapsedRealtime();
        if (now - lastRedirect < 1500) return;                      // never loop
        lastRedirect = now;
        Log.i("MyHome", "helper: stock launcher in front -> My Home");
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /** The one-time allowance ends as soon as My Home is shown again. */
    static void clearAllowance(Context c) {
        prefs(c).edit().putLong(PREF_ALLOW, 0).apply();
    }

    @Override public void onInterrupt() { }
}
