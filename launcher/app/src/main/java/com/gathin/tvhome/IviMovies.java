package com.gathin.tvhome;

import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * New movies for a row of their own: the most popular films of this year and last in ivi's public catalogue (no key),
 * with their wide promo picture, synopsis and Kinopoisk rating. No app on the TV publishes new films itself. Read off
 * the main thread, kept for six hours.
 */
final class IviMovies {
    private static final long KEEP_MS = 6 * 60 * 60 * 1000L;
    private static List<TvRows.Program> cached = Collections.emptyList();
    private static long cachedAt;
    /** Title -> TMDB movie id, for apps that open films by their TMDB page (NUM). From Wikidata, by Kinopoisk id. */
    private static Map<String, String> tmdb = Collections.emptyMap();

    private IviMovies() { }

    /** The film's TMDB id, or null when Wikidata does not know it (yet). Valid after load(). */
    static synchronized String tmdbId(String title) {
        return tmdb.get(title);
    }

    /** Kinopoisk id -> TMDB id for these films, in one Wikidata query; empty when it cannot be reached. */
    private static Map<String, String> tmdbByKinopoisk(List<String> kpIds) {
        Map<String, String> out = new HashMap<>();
        if (kpIds.isEmpty()) return out;
        StringBuilder values = new StringBuilder();
        for (String id : kpIds) values.append('"').append(id).append("\" ");
        String sparql = "SELECT ?kp ?tmdb WHERE { VALUES ?kp { " + values + "} ?f wdt:P2603 ?kp . ?f wdt:P4947 ?tmdb . }";
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL("https://query.wikidata.org/sparql?format=json&query="
                    + Uri.encode(sparql)).openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(15000);
            // Wikidata asks every client to say who it is.
            http.setRequestProperty("User-Agent", "tvhome github.com/Gathin23/cleanyourtv");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0 && body.size() < 1024 * 1024; ) body.write(buf, 0, n);
            }
            JSONArray rows = new JSONObject(body.toString("UTF-8")).getJSONObject("results").getJSONArray("bindings");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                out.put(row.getJSONObject("kp").getString("value"), row.getJSONObject("tmdb").getString("value"));
            }
        } catch (Exception e) {
            Log.w("TvHome.Movies", "Wikidata lookup failed: " + e);
        } finally {
            if (http != null) http.disconnect();
        }
        return out;
    }

    /** Programs whose link opens the film in ivi; the caller may wrap the link for another app. */
    static synchronized List<TvRows.Program> load() {
        long now = SystemClock.elapsedRealtime();
        if (!cached.isEmpty() && now - cachedAt < KEEP_MS) return cached;
        int year = Calendar.getInstance().get(Calendar.YEAR);
        List<TvRows.Program> movies = new ArrayList<>();
        Map<String, String> kpByTitle = new HashMap<>();
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL("https://api.ivi.ru/mobileapi/catalogue/v5/?app_version=870&category=14"
                    + "&sort=pop&from=0&to=29&year_from=" + (year - 1) + "&year_to=" + year).openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(10000);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0 && body.size() < 4 * 1024 * 1024; ) body.write(buf, 0, n);
            }
            JSONArray results = new JSONObject(body.toString("UTF-8")).optJSONArray("result");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String title = r.optString("title", "").trim();
                if (title.isEmpty()) continue;
                long kpId = r.optLong("kp_id", 0);
                if (kpId > 0) kpByTitle.put(title, String.valueOf(kpId));
                // The card is the vertical poster (ivi's, else Kinopoisk's by id); the wide promo picture is the backdrop.
                String picture = "", poster = "";
                JSONArray promo = r.optJSONArray("promo_images");
                if (promo != null && promo.length() > 0) picture = promo.getJSONObject(0).optString("url", "");
                if (picture.isEmpty() && r.optJSONObject("thumb_originals") != null)
                    picture = r.optJSONObject("thumb_originals").optString("path", "");
                if (r.optJSONObject("poster_originals") != null)
                    poster = r.optJSONObject("poster_originals").optString("path", "");
                if (poster.isEmpty() && kpId > 0) poster = "https://kinopoiskapiunofficial.tech/images/posters/kp/" + kpId + ".jpg";
                if (poster.isEmpty()) continue;
                String desc = r.optString("synopsis", "");
                if (desc.isEmpty()) desc = r.optString("description", "").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                StringBuilder meta = new StringBuilder();
                int y = r.optInt("year", 0);
                if (y > 0) meta.append(y);
                String kp = r.optString("kp_rating", "");
                if (!kp.isEmpty() && !kp.startsWith("0")) {
                    if (meta.length() > 0) meta.append("  ·  ");
                    meta.append("Кинопоиск ").append(kp.length() > 3 ? kp.substring(0, 3) : kp);
                }
                Intent watch = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ivi.ru/watch/" + r.optLong("id")))
                        .setPackage(IviInfo.PACKAGE);
                movies.add(new TvRows.Program(title, "", poster, watch.toUri(Intent.URI_INTENT_SCHEME), desc,
                        meta.toString(), -1, results.length() - i, -1, "", picture));
            }
        } catch (Exception e) {
            Log.w("TvHome.Movies", "Could not read new movies: " + e);
            return cached;   // the last good list, or none
        } finally {
            if (http != null) http.disconnect();
        }
        if (!movies.isEmpty()) {
            Map<String, String> byKp = tmdbByKinopoisk(new ArrayList<>(kpByTitle.values()));
            Map<String, String> byTitle = new HashMap<>();
            for (Map.Entry<String, String> e : kpByTitle.entrySet()) {
                String id = byKp.get(e.getValue());
                if (id != null) byTitle.put(e.getKey(), id);
            }
            tmdb = byTitle;
            cached = movies;
            cachedAt = now;
        }
        return movies;
    }
}
