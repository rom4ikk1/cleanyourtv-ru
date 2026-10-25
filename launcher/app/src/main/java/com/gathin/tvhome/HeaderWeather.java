package com.gathin.tvhome;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The temperature and conditions beside the date (config.xml show_weather), from Open-Meteo: no account or key, and
 * only the configured latitude and longitude are sent. Reads only while Home is resumed, every 30 minutes, 5 after a
 * failure; the last answer stays on screen in between.
 */
final class HeaderWeather {
    private static final long REFRESH_MS = 30 * 60_000L, RETRY_MS = 5 * 60_000L;

    private final View block;
    private final TextView temp, text;
    private final String lat, lon;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private volatile Handler bg;
    /** Uptime of the last good answer, or 0. */
    private long fetchedAt;

    HeaderWeather(View block, TextView temp, TextView text, String lat, String lon) {
        this.block = block;
        this.temp = temp;
        this.text = text;
        this.lat = lat.trim();
        this.lon = lon.trim();
    }

    void start() {
        if (thread != null || lat.isEmpty() || lon.isEmpty()) return;
        thread = new HandlerThread("weather");
        thread.start();
        Handler run = new Handler(thread.getLooper());
        bg = run;
        long age = fetchedAt == 0 ? Long.MAX_VALUE : SystemClock.uptimeMillis() - fetchedAt;
        run.postDelayed(new Runnable() {
            @Override public void run() {
                if (bg != run) return;
                boolean ok = fetch();
                if (bg == run) run.postDelayed(this, ok ? REFRESH_MS : RETRY_MS);
            }
        }, age >= REFRESH_MS ? 0 : REFRESH_MS - age);
    }

    void stop() {
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    /** Open-Meteo first; MET Norway when it cannot be reached (some ISPs block Open-Meteo's servers). */
    private boolean fetch() {
        try {
            JSONObject current = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day&timezone=auto"))
                    .getJSONObject("current");
            show(current.getDouble("temperature_2m"), current.getInt("weather_code"), current.optInt("is_day", 1) == 1);
            return true;
        } catch (Exception e) {
            Log.w("TvHome.Weather", "Open-Meteo failed: " + e);
        }
        try {
            JSONObject now = new JSONObject(get("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat="
                    + lat + "&lon=" + lon)).getJSONObject("properties").getJSONArray("timeseries")
                    .getJSONObject(0).getJSONObject("data");
            String symbol = now.getJSONObject("next_1_hours").getJSONObject("summary").getString("symbol_code");
            show(now.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature"),
                    wmo(symbol), !symbol.endsWith("_night"));
            return true;
        } catch (Exception e) {
            Log.w("TvHome.Weather", "MET Norway failed: " + e);
            return false;
        }
    }

    private void show(double celsius, int code, boolean day) {
        long degrees = Math.round(celsius);
        String t = (degrees > 0 ? "+" : "") + String.format(Locale.ROOT, "%d°", degrees);
        fetchedAt = SystemClock.uptimeMillis();
        main.post(() -> {
            temp.setText(icon(code, day) + "  " + t);
            text.setText(describe(code));
            block.setVisibility(View.VISIBLE);
        });
    }

    private static String get(String address) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
        try {
            http.setConnectTimeout(10_000);
            http.setReadTimeout(10_000);
            // MET Norway refuses requests without an identifying User-Agent.
            http.setRequestProperty("User-Agent", "tvhome github.com/Gathin23/cleanyourtv");
            if (http.getResponseCode() != 200) throw new Exception("HTTP " + http.getResponseCode());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0 && body.size() < 512 * 1024; ) body.write(buf, 0, n);
            }
            return body.toString("UTF-8");
        } finally {
            http.disconnect();
        }
    }

    /** A MET Norway symbol (lightrainshowers_day) as the WMO code group the rest of this class understands. */
    private static int wmo(String symbol) {
        if (symbol.contains("thunder")) return 95;
        if (symbol.contains("snow") || symbol.contains("sleet")) return 71;
        if (symbol.contains("rain")) return 61;
        if (symbol.startsWith("fog")) return 45;
        if (symbol.startsWith("cloudy")) return 3;
        if (symbol.startsWith("partlycloudy") || symbol.startsWith("fair")) return 2;
        return 0;
    }

    private static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀" : "☾";
        if (code <= 2) return "⛅";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "≋";
        if (code >= 95) return "⚡";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄";
        return "☂";
    }

    private String describe(int code) {
        int res;
        if (code == 0) res = R.string.weather_clear;
        else if (code <= 2) res = R.string.weather_partly_cloudy;
        else if (code == 3) res = R.string.weather_overcast;
        else if (code == 45 || code == 48) res = R.string.weather_fog;
        else if (code >= 51 && code <= 57) res = R.string.weather_drizzle;
        else if (code >= 71 && code <= 77 || code == 85 || code == 86) res = R.string.weather_snow;
        else if (code >= 95) res = R.string.weather_thunder;
        else res = R.string.weather_rain;
        return text.getContext().getString(res);
    }
}
