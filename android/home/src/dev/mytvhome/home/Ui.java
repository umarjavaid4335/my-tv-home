package dev.mytvhome.home;

import android.content.Context;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.TextView;

final class Ui {
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, float sp, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Soft shadow so text stays legible over any photo. */
    static TextView shadowed(TextView t) {
        t.setShadowLayer(14f, 0f, 2f, 0x66000000);
        return t;
    }

    /** True when the system animator scale is 0 (the "remove animations" accessibility setting). */
    static boolean reducedMotion(Context c) {
        return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }
}
