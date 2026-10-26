package dev.mytvhome.home;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.ArrayList;

/**
 * Full-screen player for free live streams (HLS via the platform MediaPlayer - no extra libraries).
 * Up/Down = previous/next channel in the same tab, OK = show the channel banner, Back = leave.
 */
public class PlayerActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LiveTv live;
    private ArrayList<String> zap;
    private int index;
    private VideoView video;
    private TextView bannerName, bannerInfo;
    private View banner;
    private final Runnable hideBanner = () -> banner.animate().alpha(0f).setDuration(300).start();
    private final Runnable stalled = () -> showInfo("Not available right now - Up/Down for another channel");

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // no screensaver while watching
        live = new LiveTv(this);
        String tab = getIntent().getStringExtra("tab");
        zap = live.streamNames(tab);
        index = Math.max(0, zap.indexOf(getIntent().getStringExtra("name")));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        GlassPanel b = new GlassPanel(this, 22);       // no backdrop here -> GlassPanel's dark translucent fallback
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16));
        bannerName = Ui.text(this, 26, Ui.MEDIUM, 0xFFFFFFFF);
        bannerInfo = Ui.text(this, 14, Ui.REGULAR, 0xCCFFFFFF);
        col.addView(bannerName);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-2, -2);
        ip.topMargin = Ui.dp(this, 6);
        col.addView(bannerInfo, ip);
        b.addView(col);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        bp.setMargins(Ui.dp(this, 64), 0, 0, Ui.dp(this, 48));
        root.addView(b, bp);
        banner = b;
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            main.removeCallbacks(stalled);
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) showInfo("Buffering…");
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) showInfo(null);
                return false;
            });
            showInfo(null);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Log.w("MyHome", "stream error " + what + "/" + extra + " on " + current());
            main.removeCallbacks(stalled);
            showInfo("Not available right now - Up/Down for another channel");
            return true;
        });
        play();
    }

    private String current() { return zap.isEmpty() ? "" : zap.get(index); }

    private void play() {
        if (zap.isEmpty()) { finish(); return; }
        LiveTv.Channel c = live.find(current());
        if (c == null || c.url == null) { finish(); return; }
        Log.i("MyHome", "play " + c.name);
        video.stopPlayback();
        video.setVideoURI(Uri.parse(c.url), c.headers.isEmpty() ? null : c.headers);
        video.start();
        bannerName.setText(c.name);
        showInfo("Loading…");
        main.removeCallbacks(stalled);
        main.postDelayed(stalled, 20000);
    }

    private void showInfo(String info) {
        bannerInfo.setText((index + 1) + " / " + zap.size() + "   ·   Up/Down: change channel   ·   Back: close"
                + (info == null ? "" : "\n" + info));
        banner.animate().cancel();
        banner.setAlpha(1f);
        main.removeCallbacks(hideBanner);
        if (info == null) main.postDelayed(hideBanner, 4000);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) {
            index = (index + 1) % zap.size(); play(); return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            index = (index - 1 + zap.size()) % zap.size(); play(); return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_INFO) {
            showInfo(null); return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onStop() {
        super.onStop();
        video.stopPlayback();
        main.removeCallbacksAndMessages(null);
        finish();                                       // leaving = stop streaming (no background data use)
    }
}
