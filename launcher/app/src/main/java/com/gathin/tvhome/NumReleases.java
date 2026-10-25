package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * NUM's own New movies list, so the row on Home matches the one inside NUM: the gzip'd releases file NUM itself reads
 * (num_releases_url), newest first, with each film's TMDB id. It has no pictures or descriptions (NUM takes those from
 * TMDB with its own key), so they come from ivi's catalogue where ivi has the same title; OK opens the film's TMDB page
 * in NUM. Read off the main thread, kept for an hour (NUM's list changes several times a day).
 */
final class NumReleases {
    private static final long KEEP_MS = 60 * 60 * 1000L;
    private static final int MAX = 30, SCAN = 90;
    private static List<TvRows.Program> cached = Collections.emptyList();
    private static long cachedAt;

    private static final SparseArray<String> GENRES = new SparseArray<>();
    static {
        int[] ids = {28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 10770, 53, 10752, 37};
        String[] names = {"Боевик", "Приключения", "Мультфильм", "Комедия", "Криминал", "Документальный", "Драма",
                "Семейный", "Фэнтези", "История", "Ужасы", "Музыка", "Детектив", "Мелодрама", "Фантастика",
                "Телефильм", "Триллер", "Военный", "Вестерн"};
        for (int i = 0; i < ids.length; i++) GENRES.put(ids[i], names[i]);
    }

    private NumReleases() { }

    static synchronized List<TvRows.Program> load(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (!cached.isEmpty() && now - cachedAt < KEEP_MS) return cached;
        ComponentName details = ComponentName.unflattenFromString(context.getString(R.string.search_num_activity));
        List<TvRows.Program> movies = new ArrayList<>();
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(context.getString(R.string.num_releases_url)).openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(15000);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            // The file itself is gzip, not the transfer: unpack it whatever headers come with it.
            try (InputStream in = new GZIPInputStream(http.getInputStream())) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0 && body.size() < 16 * 1024 * 1024; ) body.write(buf, 0, n);
            }
            JSONArray items = new JSONObject(body.toString("UTF-8")).getJSONArray("items");
            // More films than the row shows: those with no poster anywhere are left out, the next ones fill in.
            for (int i = 0; i < items.length() && movies.size() < SCAN; i++) {
                JSONObject item = items.getJSONObject(i);
                JSONArray torrents = item.optJSONArray("torrent");
                if (torrents == null || torrents.length() == 0) continue;
                String title = torrents.getJSONObject(0).optString("name", "").trim();
                long id = item.optLong("id", 0);
                if (title.isEmpty() || id <= 0) continue;
                String type = "tv".equals(item.optString("media_type")) ? "tv" : "movie";

                StringBuilder meta = new StringBuilder();
                String date = item.optString("release_date", "");
                if (date.length() >= 4) meta.append(date.substring(date.length() - 4));
                double vote = item.optDouble("vote_average", 0);
                if (vote > 0) append(meta, "TMDB " + String.format(java.util.Locale.ROOT, "%.1f", vote));
                JSONArray genreIds = item.optJSONArray("genre_ids");
                StringBuilder genres = new StringBuilder();
                for (int g = 0; genreIds != null && g < genreIds.length() && g < 3; g++) {
                    String name = GENRES.get(genreIds.optInt(g));
                    if (name == null) continue;
                    if (genres.length() > 0) genres.append(", ");
                    genres.append(name);
                }
                if (genres.length() > 0) append(meta, genres.toString());

                Intent page = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/" + type + "/" + id))
                        .setComponent(details);
                movies.add(new TvRows.Program(title, "", "", page.toUri(Intent.URI_INTENT_SCHEME), "",
                        meta.toString(), -1, items.length() - i, -1, genres.toString()));
            }
        } catch (Exception e) {
            Log.w("TvHome.Num", "Could not read NUM's new movies: " + e);
            return cached;
        } finally {
            if (http != null) http.disconnect();
        }
        if (!movies.isEmpty()) {
            movies = withIvi(withKinopoiskPosters(movies));
            cached = movies;
            cachedAt = now;
        }
        return movies;
    }

    /**
     * In NUM's order, up to MAX films that have a poster: ivi's description and wide picture where ivi has the same
     * title, and ivi's poster for those Kinopoisk had none. The rest is left out.
     */
    private static List<TvRows.Program> withIvi(List<TvRows.Program> movies) {
        List<TvRows.Program> out = new ArrayList<>();
        for (TvRows.Program p : movies) {
            if (out.size() >= MAX) break;
            IviInfo.Info ivi = IviInfo.sameTitle(p.title);
            String poster = !p.poster.isEmpty() ? p.poster : ivi != null ? ivi.poster : "";
            if (poster.isEmpty()) continue;
            out.add(new TvRows.Program(p.title, p.subtitle, poster, p.intentUri, ivi != null ? ivi.description : "",
                    p.meta, p.progress, p.order, p.channelId, p.genre, ivi != null ? ivi.picture : ""));
        }
        return out;
    }

    /**
     * Films ivi has no picture for get their Kinopoisk poster: Wikidata maps the TMDB id to the Kinopoisk id
     * (wdt:P4947 -> wdt:P2603) in one query, and kinopoiskapiunofficial.tech serves the poster by that id.
     */
    private static List<TvRows.Program> withKinopoiskPosters(List<TvRows.Program> movies) {
        StringBuilder values = new StringBuilder();
        for (TvRows.Program p : movies) {
            String id = tmdbId(p);
            if (p.poster.isEmpty() && id != null) values.append('"').append(id).append("\" ");
        }
        if (values.length() == 0) return movies;
        java.util.Map<String, String> kpByTmdb = new java.util.HashMap<>();
        HttpURLConnection http = null;
        try {
            String sparql = "SELECT ?tmdb ?kp WHERE { VALUES ?tmdb { " + values + "} ?f wdt:P4947 ?tmdb . ?f wdt:P2603 ?kp . }";
            http = (HttpURLConnection) new URL("https://query.wikidata.org/sparql?format=json&query="
                    + Uri.encode(sparql)).openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(15000);
            http.setRequestProperty("User-Agent", "tvhome github.com/Gathin23/cleanyourtv");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0 && body.size() < 1024 * 1024; ) body.write(buf, 0, n);
            }
            JSONArray rows = new JSONObject(body.toString("UTF-8")).getJSONObject("results").getJSONArray("bindings");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                kpByTmdb.put(row.getJSONObject("tmdb").getString("value"), row.getJSONObject("kp").getString("value"));
            }
        } catch (Exception e) {
            Log.w("TvHome.Num", "Wikidata lookup failed: " + e);
            return movies;
        } finally {
            if (http != null) http.disconnect();
        }
        List<TvRows.Program> out = new ArrayList<>(movies.size());
        for (TvRows.Program p : movies) {
            String kp = p.poster.isEmpty() ? kpByTmdb.get(tmdbId(p)) : null;
            out.add(kp == null ? p : new TvRows.Program(p.title, p.subtitle,
                    "https://kinopoiskapiunofficial.tech/images/posters/kp/" + kp + ".jpg", p.intentUri,
                    p.description, p.meta, p.progress, p.order, p.channelId, p.genre));
        }
        return out;
    }

    /** The TMDB id at the end of the film's NUM link (…/movie/<id>), or null. */
    private static String tmdbId(TvRows.Program p) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("themoviedb\\.org/movie/(\\d+)").matcher(p.intentUri);
        return m.find() ? m.group(1) : null;
    }

    private static void append(StringBuilder meta, String part) {
        if (meta.length() > 0) meta.append("  ·  ");
        meta.append(part);
    }
}
