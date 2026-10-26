package dev.mytvhome.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apps that are actually installed with a TV launcher entry (queried, never guessed), plus the user's
 * dock order and hidden set. Packages not yet seen in the saved order are appended; streaming apps from
 * DEFAULT_SHOWN are visible by default, everything else starts hidden.
 */
final class AppRepo {
    static final class App {
        final String pkg, label;
        final ComponentName component;
        final ResolveInfo info;
        App(String pkg, String label, ComponentName cn, ResolveInfo ri) { this.pkg = pkg; this.label = label; component = cn; info = ri; }
    }

    // Verified on this TV via `cmd package query-activities ... LEANBACK_LAUNCHER` (see PROGRESS.md).
    static final List<String> DEFAULT_SHOWN = Arrays.asList(
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "com.release.aryzaptv", "com.google.android.youtube.tvmusic", "com.tcl.tv");
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(
            "dev.mytvhome.home", "com.android.tv.settings"));

    private final Context ctx;
    private final PackageManager pm;
    private final SharedPreferences prefs;

    AppRepo(Context c) {
        ctx = c;
        pm = c.getPackageManager();
        prefs = c.getSharedPreferences("dock", Context.MODE_PRIVATE);
    }

    /** All launchable TV apps in dock order (shown and hidden). */
    List<App> all() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> found = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (NEVER.contains(pkg) || found.containsKey(pkg)) continue;
            found.put(pkg, new App(pkg, String.valueOf(ri.loadLabel(pm)),
                    new ComponentName(pkg, ri.activityInfo.name), ri));
        }
        List<String> order = order();
        List<App> out = new ArrayList<>();
        for (String p : order) if (found.containsKey(p)) out.add(found.remove(p));
        List<App> rest = new ArrayList<>(found.values());
        Collections.sort(rest, (a, b) -> {
            int ia = DEFAULT_SHOWN.indexOf(a.pkg), ib = DEFAULT_SHOWN.indexOf(b.pkg);
            if (ia >= 0 || ib >= 0) return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
            return a.label.compareToIgnoreCase(b.label);
        });
        out.addAll(rest);
        if (!prefs.contains("hidden")) {               // first run: hide everything that isn't a streaming app
            Set<String> hidden = new HashSet<>();
            for (App a : out) if (!DEFAULT_SHOWN.contains(a.pkg)) hidden.add(a.pkg);
            prefs.edit().putStringSet("hidden", hidden).apply();
        }
        saveOrder(out);
        return out;
    }

    List<App> shown() {
        List<App> out = new ArrayList<>();
        Set<String> hidden = hidden();
        for (App a : all()) if (!hidden.contains(a.pkg)) out.add(a);
        return out;
    }

    Set<String> hidden() { return new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet())); }

    void setHidden(String pkg, boolean hide) {
        Set<String> h = hidden();
        if (hide) h.add(pkg); else h.remove(pkg);
        prefs.edit().putStringSet("hidden", h).apply();
    }

    /** Move pkg one step earlier (-1) or later (+1) among all apps. */
    void move(String pkg, int dir) {
        List<String> o = new ArrayList<>(order());
        int i = o.indexOf(pkg), j = i + dir;
        if (i < 0 || j < 0 || j >= o.size()) return;
        Collections.swap(o, i, j);
        prefs.edit().putString("order", TextUtils.join(",", o)).apply();
    }

    private List<String> order() {
        String s = prefs.getString("order", "");
        return s.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    private void saveOrder(List<App> apps) {
        List<String> o = new ArrayList<>();
        for (App a : apps) o.add(a.pkg);
        prefs.edit().putString("order", TextUtils.join(",", o)).apply();
    }

    /** TV banner if the app ships one (activity first, then application), else null. */
    Drawable banner(App a) {
        Drawable d = a.info.activityInfo.loadBanner(pm);
        if (d == null) d = a.info.activityInfo.applicationInfo.loadBanner(pm);
        return d;
    }

    Drawable icon(App a) { return a.info.loadIcon(pm); }

    Intent launchIntent(App a) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(a.component);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
