package dev.mytvhome.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HomeActivity extends Activity {
    private static final String TAG = "MyHome";
    private static final int WHITE = 0xFFFFFFFF, WHITE_80 = 0xCCFFFFFF, WHITE_60 = 0x99FFFFFF;
    // Layout grid (dp). 64 side margins, 48 top/bottom, 16 between stacked panels.
    private static final int M = 64, EDGE = 48, GAP = 16, RIGHT_COL_W = 380, PILL_H = 64,
            DOCK_TILE_H = 76, DOCK_PAD = 12;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Backdrop backdrop;
    private Weather weather;
    private AppRepo repo;
    private SharedPreferences ui;
    private boolean reduced;

    private FrameLayout root;
    private ImageView bgFront, bgBack;
    private TextView time, date, focusLabel, credit, helperNote;
    private TextView wCity, wStatus, wTemp, wCond, wHiLo;
    private Glyph wIcon;
    private LinearLayout forecast;
    private GlassPanel dockPanel, pill;
    private LinearLayout dockRow;
    private View sheet, sheetOpener;
    private ValueAnimator drift;

    private final List<JSONObject> backgrounds = new ArrayList<>();
    private final List<GlassPanel> glass = new ArrayList<>();
    private Weather.Data lastWeather;
    private boolean lastFresh;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); renderWeatherStatus(); }
    };
    private final Runnable weatherLoop = new Runnable() {
        @Override public void run() {
            weather.refresh(HomeActivity.this::onWeather);
            main.postDelayed(this, Weather.REFRESH_MS);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        long t0 = System.currentTimeMillis();
        ui = getSharedPreferences("ui", MODE_PRIVATE);
        repo = new AppRepo(this);
        weather = new Weather(this);
        backdrop = new Backdrop(this);
        reduced = Ui.reducedMotion(this);
        loadBackgroundList();
        buildLayout();
        backdrop.addListener(this::onBackdrop);
        backdrop.load("bg/" + currentBg().optString("file"));
        lastWeather = weather.cached();
        renderWeather();
        Log.i(TAG, "onCreate " + (System.currentTimeMillis() - t0) + " ms");
    }

    @Override protected void onResume() {
        super.onResume();
        HomeHelperService.clearAllowance(this);
        helperNote.setVisibility(HomeHelperService.setUpButOff(this) ? View.VISIBLE : View.GONE);
        Log.i(TAG, "resumed");
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        updateClock();
        rebuildDock();                                  // apps may have been installed/removed meanwhile
        main.removeCallbacks(weatherLoop);
        if (weather.due()) main.post(weatherLoop); else main.postDelayed(weatherLoop, Weather.REFRESH_MS);
        startDrift();
        if (sheet == null) restoreDockFocus();
    }

    @Override protected void onPause() {
        super.onPause();
        unregisterReceiver(tick);
        main.removeCallbacks(weatherLoop);
        if (drift != null) drift.cancel();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (sheet != null) closeSheet();
            return true;                                // home screen: Back never exits to a blank place
        }
        return super.onKeyDown(keyCode, e);
    }

    // ------------------------------------------------------------------ layout

    private void buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        bgBack = new ImageView(this);
        bgFront = new ImageView(this);
        for (ImageView v : new ImageView[]{bgBack, bgFront}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55000000, 0x00000000, 0x00000000, 0x80000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // One grid for everything (dp on a 960x540 screen): side margins M, top/bottom margins EDGE, gaps GAP.
        // Right column = weather card + button pill, same width and edges; dock spans the full content width.
        final int dockH = DOCK_TILE_H + 2 * DOCK_PAD;
        final int pillBottom = EDGE + dockH + GAP;
        final int weatherH = 540 - EDGE - pillBottom - PILL_H - GAP;

        // Fixed width: wrap_content measured the spanned "h:mm  AM" too narrow on this firmware and clipped "AM".
        // Top offset compensates the font's space above the digits so they line up with the weather card's top.
        root.addView(buildClock(), lp(dp(420), -2, Gravity.TOP | Gravity.START, M, EDGE - 22, 0, 0));
        root.addView(buildWeather(), lp(dp(RIGHT_COL_W), dp(weatherH), Gravity.TOP | Gravity.END, 0, EDGE, M, 0));

        // Row above the dock: focused-item name on the left (centred on the pill), utility pill on the right.
        focusLabel = Ui.shadowed(Ui.text(this, 20, Ui.MEDIUM, WHITE));
        focusLabel.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(focusLabel, lp(-2, dp(PILL_H), Gravity.BOTTOM | Gravity.START, M + 4, 0, 0, pillBottom));

        dockPanel = glass(26);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(true);
        hs.setClipToPadding(false);
        hs.setPadding(dp(DOCK_PAD), dp(DOCK_PAD), dp(DOCK_PAD), dp(DOCK_PAD));
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        hs.addView(dockRow);
        dockPanel.addView(hs, new FrameLayout.LayoutParams(-1, -2));
        dockPanel.setClipChildren(false);
        root.addView(dockPanel, lp(-1, dp(dockH), Gravity.BOTTOM, M, 0, M, EDGE));

        pill = glass(24);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(dp(8), 0, dp(8), 0);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(cell(utilityButton(Glyph.IMAGE, "Backgrounds", v -> openBackgroundSheet(v))));
        buttons.addView(cell(utilityButton(Glyph.GRID, "Arrange dock", v -> openDockSheet(v))));
        buttons.addView(cell(utilityButton(Glyph.GEAR, "Settings", v -> launch(new Intent(Settings.ACTION_SETTINGS)))));
        buttons.addView(cell(utilityButton(Glyph.HOUSE, "Android TV Home", v -> {
            HomeHelperService.allowStockOnce(this);     // the helper lets this one visit stay
            launch(new Intent(Intent.ACTION_MAIN).setClassName(HomeHelperService.STOCK, HomeHelperService.STOCK + ".MainActivity"));
        })));
        pill.addView(buttons, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pill, lp(dp(RIGHT_COL_W), dp(PILL_H), Gravity.BOTTOM | Gravity.END, 0, 0, M, pillBottom));

        // Footer line in the bottom margin, aligned to the same side margins.
        credit = Ui.text(this, 11, Ui.REGULAR, WHITE_60);
        root.addView(credit, lp(-2, -2, Gravity.BOTTOM | Gravity.END, 0, 0, M, 16));

        // Shown only when the Home-button helper was set up but the TV has switched it off (it does after a restart).
        helperNote = Ui.shadowed(Ui.text(this, 12, Ui.REGULAR, WHITE_80));
        helperNote.setText("Home button helper is off  ·  Settings › Device Preferences › Accessibility › My Home: Home button");
        helperNote.setVisibility(View.GONE);
        root.addView(helperNote, lp(-2, -2, Gravity.BOTTOM | Gravity.START, M, 0, 0, 16));

        setContentView(root);
    }

    private View buildClock() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        time = Ui.shadowed(Ui.text(this, 104, Ui.LIGHT, WHITE));   // "9:53 AM" as one spanned string
        time.setSingleLine(true);
        col.addView(time, new LinearLayout.LayoutParams(-1, -2));
        date = Ui.shadowed(Ui.text(this, 24, Ui.REGULAR, WHITE_80));
        LinearLayout.LayoutParams dpm = new LinearLayout.LayoutParams(-2, -2);
        dpm.topMargin = dp(10);
        dpm.leftMargin = dp(4);
        col.addView(date, dpm);
        return col;
    }

    private View buildWeather() {
        GlassPanel p = glass(28);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);        // card height comes from the grid; centre the content
        col.setPadding(dp(24), dp(14), dp(24), dp(12));

        LinearLayout head = new LinearLayout(this);
        wCity = Ui.text(this, 17, Ui.MEDIUM, WHITE);
        wCity.setText(Weather.CITY);
        wStatus = Ui.text(this, 12, Ui.REGULAR, WHITE_60);
        wStatus.setGravity(Gravity.END);
        head.addView(wCity, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(wStatus, new LinearLayout.LayoutParams(-2, -2));
        col.addView(head);

        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        wIcon = new Glyph(this);
        now.addView(wIcon, new LinearLayout.LayoutParams(dp(60), dp(60)));
        wTemp = Ui.text(this, 60, Ui.LIGHT, WHITE);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = dp(12);
        now.addView(wTemp, tp);
        LinearLayout desc = new LinearLayout(this);
        desc.setOrientation(LinearLayout.VERTICAL);
        wCond = Ui.text(this, 18, Ui.REGULAR, WHITE);
        wHiLo = Ui.text(this, 14, Ui.REGULAR, WHITE_80);
        desc.addView(wCond);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = dp(6);
        desc.addView(wHiLo, hp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = dp(18);
        now.addView(desc, dlp);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(10);
        col.addView(now, np);

        View rule = new View(this);
        rule.setBackgroundColor(0x2EFFFFFF);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(1));
        rp.topMargin = dp(12);
        rp.bottomMargin = dp(10);
        col.addView(rule, rp);

        forecast = new LinearLayout(this);
        col.addView(forecast, new LinearLayout.LayoutParams(-1, -2));

        TextView attr = Ui.text(this, 9, Ui.REGULAR, 0x80FFFFFF);
        attr.setText("Weather data by Open-Meteo.com");
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.topMargin = dp(10);
        col.addView(attr, ap);
        p.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return p;
    }

    /** Equal-width slot so the pill's buttons are spread evenly across the right column. */
    private View cell(View button) {
        FrameLayout c = new FrameLayout(this);
        c.setClipChildren(false);
        ViewGroup.LayoutParams bp = button.getLayoutParams();
        c.addView(button, new FrameLayout.LayoutParams(bp.width, bp.height, Gravity.CENTER));
        c.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        return c;
    }

    private View utilityButton(int kind, final String label, final View.OnClickListener onClick) {
        FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        b.setContentDescription(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x00FFFFFF);
        b.setBackground(bg);
        Glyph g = new Glyph(this);
        g.setKind(kind);
        b.addView(g, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        b.setOnClickListener(onClick);
        b.setOnKeyListener((v, code, e) -> {           // Down from the pill returns to the last app, not the nearest
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.getAction() == KeyEvent.ACTION_DOWN && dockRow.getChildCount() > 0) {
                restoreDockFocus();
                return true;
            }
            return false;
        });
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x40FFFFFF : 0x00FFFFFF);
            bg.setStroke(has ? dp(2) : 0, WHITE);
            animateScale(v, has ? 1.12f : 1f);
            if (has) focusLabel.setText(label);
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(48), dp(48));
        p.leftMargin = dp(3);
        p.rightMargin = dp(3);
        b.setLayoutParams(p);
        return b;
    }

    // ------------------------------------------------------------------ dock

    private void rebuildDock() {
        String focusedPkg = null;
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof String) focusedPkg = (String) f.getTag();
        dockRow.removeAllViews();
        for (AppRepo.App a : repo.shown()) dockRow.addView(tile(a));
        if (focusedPkg != null) focusTile(focusedPkg);
    }

    /** Outer frame = focus rim (drawn outside the banner so it shows on white banners too); inner = clipped banner. */
    private View tile(final AppRepo.App a) {
        FrameLayout outer = new FrameLayout(this);
        outer.setTag(a.pkg);
        outer.setFocusable(true);
        outer.setContentDescription(a.label);
        outer.setPadding(dp(6), dp(6), dp(6), dp(6));     // 3dp rim + 3dp gap, so the rim reads on white banners
        final GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(dp(16));
        rim.setColor(0x00000000);
        outer.setBackground(rim);
        FrameLayout t = new FrameLayout(this);
        outer.addView(t, new FrameLayout.LayoutParams(-1, -1));
        t.setClipToOutline(true);
        final float radius = dp(10);
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        Drawable banner = repo.banner(a);
        if (banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
            t.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {                                        // no TV banner: icon + name on a neutral card
            t.setBackgroundColor(0xFF2A3038);
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(repo.icon(a));
            c.addView(iv, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView n = Ui.text(this, 11, Ui.REGULAR, WHITE);
            n.setText(a.label);
            n.setSingleLine(true);
            c.addView(n);
            t.addView(c, new FrameLayout.LayoutParams(-1, -1));
        }
        outer.setAlpha(0.88f);
        outer.setOnFocusChangeListener((v, has) -> {
            rim.setStroke(has ? dp(3) : 0, WHITE);
            v.setAlpha(has ? 1f : 0.88f);
            animateScale(v, has ? 1.08f : 1f);
            if (has) {
                focusLabel.setText(a.label);
                ui.edit().putString("lastPkg", a.pkg).apply();
            }
        });
        outer.setOnClickListener(v -> launch(repo.launchIntent(a)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(128), dp(76));
        p.leftMargin = dp(2);
        p.rightMargin = dp(2);
        outer.setLayoutParams(p);
        return outer;
    }

    private void restoreDockFocus() {
        String pkg = ui.getString("lastPkg", null);
        if (pkg == null || !focusTile(pkg)) {
            if (dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
            else pill.requestFocus();
        }
    }

    private boolean focusTile(String pkg) {
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View c = dockRow.getChildAt(i);
            if (pkg.equals(c.getTag())) { c.requestFocus(); return true; }
        }
        return false;
    }

    private void launch(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "launch failed: " + i, e);
            focusLabel.setText("Couldn't open that app");
        }
    }

    // ------------------------------------------------------------------ clock + weather

    private void updateClock() {
        Date now = new Date();
        SpannableStringBuilder s = new SpannableStringBuilder(new SimpleDateFormat("h:mm", Locale.US).format(now));
        int start = s.length();
        s.append("  ").append(new SimpleDateFormat("a", Locale.US).format(now));
        s.setSpan(new RelativeSizeSpan(0.28f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(WHITE_80), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        time.setText(s);
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.UK).format(now));
    }

    private void onWeather(Weather.Data d, boolean fresh) {
        lastWeather = d;
        lastFresh = fresh;
        renderWeather();
    }

    private void renderWeather() {
        Weather.Data d = lastWeather;
        if (d == null) {
            wTemp.setText("—");
            wCond.setText("Weather unavailable");
            wHiLo.setText("Waiting for network");
            wIcon.setKind(Glyph.UNKNOWN);
            renderWeatherStatus();
            return;
        }
        wTemp.setText(Math.round(d.temp) + "°");
        wCond.setText(Weather.describe(d.code));
        wIcon.setKind(Glyph.forWmo(d.code, d.day));
        if (d.dMax.length > 0) {
            wHiLo.setText("H " + Math.round(d.dMax[0]) + "°  L " + Math.round(d.dMin[0]) + "°  · Feels " + Math.round(d.feels) + "°");
        }
        forecast.removeAllViews();
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US), out = new SimpleDateFormat("EEE", Locale.UK);
        for (int i = 1; i < d.dates.length && i <= 4; i++) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView day = Ui.text(this, 13, Ui.MEDIUM, WHITE_80);
            try { day.setText(out.format(in.parse(d.dates[i]))); } catch (Exception e) { day.setText(d.dates[i]); }
            Glyph g = new Glyph(this);
            g.setKind(Glyph.forWmo(d.dCode[i], true));
            TextView t = Ui.text(this, 14, Ui.REGULAR, WHITE);
            t.setText(Math.round(d.dMax[i]) + "°  " + Math.round(d.dMin[i]) + "°");
            c.addView(day);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(28), dp(28));
            gp.topMargin = dp(6);
            gp.bottomMargin = dp(6);
            c.addView(g, gp);
            c.addView(t);
            forecast.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
        }
        renderWeatherStatus();
    }

    private void renderWeatherStatus() {
        if (wStatus == null) return;
        Weather.Data d = lastWeather;
        if (d == null) { wStatus.setText("Offline"); return; }
        long age = System.currentTimeMillis() - d.fetchedAt;
        String at = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(d.fetchedAt));
        if (age > Weather.STALE_MS) wStatus.setText("Offline · last updated " + at);
        else if (!lastFresh && age > Weather.REFRESH_MS + 60000) wStatus.setText("Can't refresh · " + at);
        else wStatus.setText("Updated " + at);
    }

    // ------------------------------------------------------------------ backgrounds

    private void loadBackgroundList() {
        try (InputStream in = getAssets().open("bg/credits.json")) {
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            JSONArray arr = new JSONArray(new String(b, 0, n, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) backgrounds.add(arr.getJSONObject(i));
        } catch (Exception e) {
            Log.e(TAG, "credits.json", e);
        }
    }

    private JSONObject currentBg() {
        int i = Math.max(0, Math.min(backgrounds.size() - 1, ui.getInt("bg", 0)));
        return backgrounds.isEmpty() ? new JSONObject() : backgrounds.get(i);
    }

    private void onBackdrop(Bitmap full, Bitmap blurred) {
        JSONObject c = currentBg();
        credit.setText("Photo: " + c.optString("author") + " / Unsplash");
        if (bgFront.getDrawable() == null || reduced) {
            bgFront.setImageBitmap(full);
            return;
        }
        bgBack.setImageDrawable(bgFront.getDrawable());
        bgFront.setAlpha(0f);
        bgFront.setImageBitmap(full);
        bgFront.animate().alpha(1f).setDuration(450).start();
    }

    private void setBackground(int index) {
        ui.edit().putInt("bg", index).apply();
        backdrop.load("bg/" + backgrounds.get(index).optString("file"));
    }

    private void startDrift() {
        if (drift != null) drift.cancel();
        bgFront.setScaleX(1f);
        bgFront.setScaleY(1f);
        if (reduced || !ui.getBoolean("motion", false)) return;
        drift = ValueAnimator.ofFloat(1f, 1.06f);
        drift.setDuration(45000);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.addUpdateListener(a -> {
            float s = (float) a.getAnimatedValue();
            bgFront.setScaleX(s);
            bgFront.setScaleY(s);
        });
        drift.start();
    }

    // ------------------------------------------------------------------ sheets (overlays)

    private void openBackgroundSheet(View opener) {
        GlassPanel p = glass(28);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(28), dp(22), dp(28), dp(24));
        col.setClipChildren(false);
        col.setClipToPadding(false);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, 22, Ui.MEDIUM, WHITE);
        title.setText("Background");
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        final TextView motion = chip(motionLabel());
        motion.setOnClickListener(v -> {
            ui.edit().putBoolean("motion", !ui.getBoolean("motion", false)).apply();
            motion.setText(motionLabel());
            startDrift();
        });
        head.addView(motion);
        col.addView(head);

        final TextView caption = Ui.text(this, 14, Ui.REGULAR, WHITE_80);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = dp(6);
        col.addView(caption, cp);

        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, dp(18), 0, dp(4));
        final int current = ui.getInt("bg", 0);
        View currentThumb = null;
        for (int i = 0; i < backgrounds.size(); i++) {
            final int idx = i;
            final JSONObject b = backgrounds.get(i);
            final FrameLayout t = new FrameLayout(this);
            t.setFocusable(true);
            t.setClipToOutline(true);
            t.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
            });
            final ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            t.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            loadThumb(b.optString("file"), iv);
            final GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(10));
            ring.setStroke(idx == current ? dp(2) : 0, 0xB3FFFFFF);
            t.setForeground(ring);
            t.setOnFocusChangeListener((v, has) -> {
                ring.setStroke(has ? dp(3) : (idx == ui.getInt("bg", 0) ? dp(2) : 0), has ? WHITE : 0xB3FFFFFF);
                animateScale(v, has ? 1.1f : 1f);
                v.setElevation(has ? dp(10) : 0);
                if (has) caption.setText(b.optString("title") + "  ·  Photo: " + b.optString("author") + " / Unsplash");
            });
            t.setOnClickListener(v -> {
                setBackground(idx);
                for (int k = 0; k < ((ViewGroup) v.getParent()).getChildCount(); k++) {
                    View o = ((ViewGroup) v.getParent()).getChildAt(k);
                    if (o != v) ((GradientDrawable) o.getForeground()).setStroke(0, 0);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(68));
            lp.rightMargin = dp(14);
            row.addView(t, lp);
            if (idx == current) currentThumb = t;
        }
        col.addView(row);
        p.addView(col);
        showSheet(p, opener, currentThumb, lp(-1, -2, Gravity.BOTTOM, M, 0, M, EDGE));
    }

    private String motionLabel() { return "Gentle motion: " + (ui.getBoolean("motion", false) ? "On" : "Off"); }

    private void openDockSheet(View opener) {
        GlassPanel p = glass(28);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(28), dp(22), dp(28), dp(18));
        TextView title = Ui.text(this, 22, Ui.MEDIUM, WHITE);
        title.setText("Arrange dock");
        col.addView(title);
        TextView hint = Ui.text(this, 14, Ui.REGULAR, WHITE_80);
        hint.setText("OK on an app shows or hides it.  Use the arrows to move it earlier or later.  Back closes.");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = dp(6);
        hp.bottomMargin = dp(10);
        col.addView(hint, hp);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(-1, dp(260)));
        p.addView(col);
        fillDockList(list, null, 1);
        showSheet(p, opener, list.getChildCount() > 0 ? ((ViewGroup) list.getChildAt(0)).getChildAt(1) : null,
                lp(dp(620), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, EDGE));
    }

    /** Rows: [earlier] [app: OK toggles] [later]. After a change, focus returns to the same control. */
    private void fillDockList(final LinearLayout list, String focusPkg, int focusCol) {
        list.removeAllViews();
        Set<String> hidden = repo.hidden();
        View toFocus = null;
        for (final AppRepo.App a : repo.all()) {
            final boolean isHidden = hidden.contains(a.pkg);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView up = chip("◀");
            up.setOnClickListener(v -> { repo.move(a.pkg, -1); fillDockList(list, a.pkg, 0); });
            TextView name = chip((isHidden ? "Hidden   " : "Shown    ") + a.label);
            name.setAlpha(isHidden ? 0.6f : 1f);
            name.setOnClickListener(v -> { repo.setHidden(a.pkg, !isHidden); fillDockList(list, a.pkg, 1); });
            TextView down = chip("▶");
            down.setOnClickListener(v -> { repo.move(a.pkg, 1); fillDockList(list, a.pkg, 2); });
            r.addView(up);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
            np.leftMargin = dp(10);
            np.rightMargin = dp(10);
            r.addView(name, np);
            r.addView(down);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = dp(6);
            list.addView(r, rp);
            if (a.pkg.equals(focusPkg)) toFocus = r.getChildAt(focusCol);
        }
        if (toFocus != null) toFocus.requestFocus();
    }

    private TextView chip(String text) {
        final TextView c = Ui.text(this, 16, Ui.REGULAR, WHITE);
        c.setText(text);
        c.setFocusable(true);
        c.setPadding(dp(16), dp(9), dp(16), dp(9));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x1AFFFFFF);
        c.setBackground(bg);
        c.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x1AFFFFFF);
            c.setTextColor(has ? 0xFF111418 : WHITE);
        });
        return c;
    }

    private void showSheet(View s, View opener, View initial, FrameLayout.LayoutParams params) {
        if (sheet != null) closeSheet();
        sheet = s;
        sheetOpener = opener;
        dockPanel.setVisibility(View.INVISIBLE);
        pill.setVisibility(View.INVISIBLE);
        focusLabel.setVisibility(View.INVISIBLE);
        root.addView(s, params);
        s.setAlpha(0f);
        s.animate().alpha(1f).setDuration(reduced ? 0 : 180).start();
        if (initial != null) initial.requestFocus(); else s.requestFocus();
    }

    private void closeSheet() {
        if (sheet == null) return;
        root.removeView(sheet);
        glass.remove(sheet);
        sheet = null;
        dockPanel.setVisibility(View.VISIBLE);
        pill.setVisibility(View.VISIBLE);
        focusLabel.setVisibility(View.VISIBLE);
        rebuildDock();
        if (sheetOpener != null) sheetOpener.requestFocus();   // focus goes back to whatever opened the sheet
        sheetOpener = null;
    }

    // ------------------------------------------------------------------ helpers

    private void loadThumb(final String file, final ImageView into) {
        new Thread(() -> {
            try (InputStream in = getAssets().open("bg/" + file)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 8;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap b = BitmapFactory.decodeStream(in, null, o);
                main.post(() -> into.setImageBitmap(b));
            } catch (Exception e) {
                Log.w(TAG, "thumb " + file, e);
            }
        }, "thumb").start();
    }

    private GlassPanel glass(float radius) {
        GlassPanel p = new GlassPanel(this, radius);
        glass.add(p);
        if (backdrop != null) backdrop.addListener(p);
        return p;
    }

    private void animateScale(View v, float s) {
        v.animate().cancel();
        if (reduced) { v.setScaleX(s); v.setScaleY(s); return; }
        v.animate().scaleX(s).scaleY(s).setDuration(120).start();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }
}
