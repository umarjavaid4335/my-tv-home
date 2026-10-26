package dev.mytvhome.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * City-level weather from Open-Meteo (free, no key, non-commercial; data CC BY 4.0 - credited on screen).
 * The last good response is cached so the card shows something offline, labelled with its age.
 */
final class Weather {
    static final String CITY = "Lahore";
    private static final String URL_STR = "https://api.open-meteo.com/v1/forecast?latitude=31.55&longitude=74.34"
            + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
            + "&timezone=Asia%2FKarachi&forecast_days=5";
    static final long REFRESH_MS = 30 * 60 * 1000L;
    static final long STALE_MS = 2 * 60 * 60 * 1000L;

    static final class Data {
        double temp, feels;
        int code;
        boolean day;
        String[] dates = new String[0];
        int[] dCode = new int[0];
        double[] dMax = new double[0], dMin = new double[0];
        long fetchedAt;
    }

    interface Callback { void onWeather(Data d, boolean fresh); }

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    Weather(Context c) { prefs = c.getSharedPreferences("weather", Context.MODE_PRIVATE); }

    Data cached() {
        String js = prefs.getString("json", null);
        if (js == null) return null;
        try { return parse(js, prefs.getLong("at", 0)); } catch (Exception e) { return null; }
    }

    boolean due() { return System.currentTimeMillis() - prefs.getLong("at", 0) > REFRESH_MS; }

    void refresh(final Callback cb) {
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                Data d = null;
                try {
                    String js = get(URL_STR);
                    long now = System.currentTimeMillis();
                    d = parse(js, now);
                    prefs.edit().putString("json", js).putLong("at", now).apply();
                } catch (Exception ignored) {
                    // offline / API trouble: keep showing the cache, flagged as not fresh
                }
                final Data out = d != null ? d : cached();
                final boolean fresh = d != null;
                busy = false;
                main.post(new Runnable() { @Override public void run() { cb.onWeather(out, fresh); } });
            }
        }, "weather").start();
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "my-tv-home/1.0");
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static Data parse(String js, long at) throws Exception {
        JSONObject o = new JSONObject(js);
        JSONObject cur = o.getJSONObject("current");
        Data d = new Data();
        d.temp = cur.getDouble("temperature_2m");
        d.feels = cur.getDouble("apparent_temperature");
        d.code = cur.getInt("weather_code");
        d.day = cur.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        JSONArray t = daily.getJSONArray("time"), wc = daily.getJSONArray("weather_code"),
                mx = daily.getJSONArray("temperature_2m_max"), mn = daily.getJSONArray("temperature_2m_min");
        int n = t.length();
        d.dates = new String[n]; d.dCode = new int[n]; d.dMax = new double[n]; d.dMin = new double[n];
        for (int i = 0; i < n; i++) {
            d.dates[i] = t.getString(i);
            d.dCode[i] = wc.getInt(i);
            d.dMax[i] = mx.getDouble(i);
            d.dMin[i] = mn.getDouble(i);
        }
        d.fetchedAt = at;
        return d;
    }

    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 65) return "Rain";
        if (code == 66 || code == 67) return "Freezing rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }
}
