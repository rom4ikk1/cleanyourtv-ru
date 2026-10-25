package com.gathin.tvhome;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * ivi publishes its Continue watching rows with a title and a picture only. Its public catalogue API (the one its own
 * apps use, no key) gives the series' description, years and ratings, which the content row shows instead.
 */
final class IviInfo {
    static final String PACKAGE = "ru.ivi.client";

    /** Description and meta line ("2009–2025  ·  Кинопоиск 7.5  ·  Иви 7.3"); both empty when nothing was found. */
    static final class Info {
        final String description, meta;

        Info(String description, String meta) {
            this.description = description;
            this.meta = meta;
        }
    }

    private static final Map<String, Info> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private IviInfo() { }

    static Info cached(String title) {
        return CACHE.get(title);
    }

    /** Looks the programme up (off the main thread) and hands the answer to done on the main thread. */
    static void fetch(String title, Consumer<Info> done) {
        Info hit = CACHE.get(title);
        if (hit != null) { done.accept(hit); return; }
        POOL.execute(() -> {
            Info info = CACHE.get(title);
            if (info == null) {
                info = lookUp(title);
                CACHE.put(title, info);
            }
            Info answer = info;
            MAIN.post(() -> done.accept(answer));
        });
    }

    /** "Праздники и подарки. Маша и Медведь - Сезон 1, Серия 5" -> the series, then the part after the episode name. */
    private static Info lookUp(String title) {
        String series = title.replaceFirst("\\s+-\\s+Сезон.*$", "").trim();
        Info info = search(series);
        int dot = series.lastIndexOf(". ");
        if (info.description.isEmpty() && dot > 0) info = search(series.substring(dot + 2).trim());
        return info;
    }

    private static Info search(String query) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL("https://api.ivi.ru/mobileapi/search/v7/?app_version=870&from=0&to=0&query="
                    + Uri.encode(query)).openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(8000);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0 && body.size() < 2 * 1024 * 1024; ) body.write(buf, 0, n);
            }
            JSONArray results = new JSONObject(body.toString("UTF-8")).optJSONArray("result");
            if (results == null || results.length() == 0) return new Info("", "");
            JSONObject r = results.getJSONObject(0);
            String desc = r.optString("short_description", "");
            if (desc.isEmpty()) desc = r.optString("synopsis", "");
            if (desc.isEmpty()) desc = r.optString("description", "").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            StringBuilder meta = new StringBuilder();
            JSONArray years = r.optJSONArray("years");
            if (years != null && years.length() > 0) {
                int first = years.optInt(0), last = years.optInt(years.length() - 1);
                meta.append(first == last ? String.valueOf(first) : first + "–" + last);
            }
            String kp = r.optString("kp_rating", "");
            if (!kp.isEmpty() && !kp.startsWith("0")) append(meta, "Кинопоиск " + trim(kp));
            double ivi = r.optDouble("ivi_rating_10", 0);
            if (ivi > 0) append(meta, "Иви " + String.format(java.util.Locale.ROOT, "%.1f", ivi));
            return new Info(desc, meta.toString());
        } catch (Exception e) {
            Log.w("TvHome.Ivi", "Lookup failed for " + query + ": " + e);
            return new Info("", "");
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static String trim(String rating) {
        return rating.length() > 3 ? rating.substring(0, 3) : rating;
    }

    private static void append(StringBuilder meta, String part) {
        if (meta.length() > 0) meta.append("  ·  ");
        meta.append(part);
    }
}
