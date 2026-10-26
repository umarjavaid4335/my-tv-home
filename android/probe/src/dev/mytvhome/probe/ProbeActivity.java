package dev.mytvhome.probe;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Minimal app used only to prove install / launch / uninstall on the TV. */
public class ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView t = new TextView(this);
        t.setText("my-tv-home probe OK\nPress Back to exit");
        t.setTextSize(48);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(Color.rgb(47, 111, 214));
        setContentView(t);
    }
}
