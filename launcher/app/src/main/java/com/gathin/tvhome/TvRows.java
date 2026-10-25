package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Continue watching and preview programs other apps publish to the TV provider (docs/watch-next.md), as rows per
 * package. Needs READ_TV_LISTINGS; without ACCESS_ALL_EPG_DATA only rows marked searchable come back, and the
 * provider refuses a selection, so whole tables are read and filtered here. Runs off the main thread.
 *
 * Rows, in order: Continue watching; the app's history-like channels (History, Favourites); Movies, Series,
 * Animated series and Cartoons, for programs whose genre text says which (NUM writes "S1 · Мультфильм, ..."); then
 * the app's other channels under their own names, with what could not be sorted.
 */
final class TvRows {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";

    static final class Program {
        final String title, subtitle, poster, intentUri;
        /** The app's description of it, and a short line such as "2024 · 7.8", either possibly empty. */
        final String description, meta;
        /** 0..1 how far it was watched, or -1 when not a Continue watching row. */
        final float progress;
        final long order;
        final long channelId;
        final String genre;
        /** A wide picture for the backdrop when the card's poster is a vertical one; empty: the poster itself. */
        final String wide;

        Program(String title, String subtitle, String poster, String intentUri, String description, String meta,
                float progress, long order, long channelId, String genre) {
            this(title, subtitle, poster, intentUri, description, meta, progress, order, channelId, genre, "");
        }

        Program(String title, String subtitle, String poster, String intentUri, String description, String meta,
                float progress, long order, long channelId, String genre, String wide) {
            this.wide = wide;
            this.title = title;
            this.subtitle = subtitle;
            this.poster = poster;
            this.intentUri = intentUri;
            this.description = description;
            this.meta = meta;
            this.progress = progress;
            this.order = order;
            this.channelId = channelId;
            this.genre = genre;
        }
    }

    static final class Row {
        final String title;
        final List<Program> items;

        Row(String title, List<Program> items) {
            this.title = title;
            this.items = items;
        }
    }

    private static final Pattern SEASON = Pattern.compile("(^|[\\s·,])S\\d+($|[\\s·,])");
    private static final Pattern PERSONAL = Pattern.compile("(?i)(истори|избранн|history|favou?rite|watchlist)");

    private TvRows() { }

    /** Package -> rows. */
    static Map<String, List<Row>> load(Context context) {
        Map<String, List<Row>> byPackage = new HashMap<>();
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.i("TvHome.Rows", "READ_TV_LISTINGS not granted");
            return byPackage;
        }
        Map<Long, String> channels = channels(context);
        Map<String, List<Program>> next = new HashMap<>(), preview = new HashMap<>();
        read(context, TvContract.WatchNextPrograms.CONTENT_URI, next, true);
        read(context, TvContract.PreviewPrograms.CONTENT_URI, preview, false);

        String continueTitle = context.getString(R.string.row_continue);
        String[] categoryTitles = {context.getString(R.string.row_movies), context.getString(R.string.row_series),
                context.getString(R.string.row_animated_series), context.getString(R.string.row_cartoons)};
        for (String pkg : union(next, preview)) {
            List<Row> rows = new ArrayList<>();
            List<Program> watching = next.get(pkg);
            if (watching != null) rows.add(new Row(continueTitle, sorted(watching)));

            Map<String, List<Program>> personal = new LinkedHashMap<>(), others = new LinkedHashMap<>();
            List<List<Program>> categories = new ArrayList<>();
            for (int i = 0; i < categoryTitles.length; i++) categories.add(new ArrayList<>());
            List<Program> programs = preview.get(pkg);
            if (programs != null) {
                for (Program p : sorted(programs)) {
                    String channel = channels.get(p.channelId);
                    if (channel == null) channel = "";
                    if (PERSONAL.matcher(channel).find()) {
                        personal.computeIfAbsent(channel, k -> new ArrayList<>()).add(p);
                        continue;
                    }
                    int category = category(p.genre);
                    if (category >= 0) categories.get(category).add(p);
                    else others.computeIfAbsent(channel, k -> new ArrayList<>()).add(p);
                }
            }
            // History before Favourites, new releases before the app's other channels; hidden_rows left out.
            List<String> hidden = Arrays.asList(context.getResources().getStringArray(R.array.hidden_rows));
            for (String name : first(personal.keySet(), HISTORY)) {
                if (!hidden.contains(shortName(name))) rows.add(new Row(shortName(name), personal.get(name)));
            }
            for (int i = 0; i < categoryTitles.length; i++) {
                if (!categories.get(i).isEmpty()) rows.add(new Row(categoryTitles[i], categories.get(i)));
            }
            for (String name : first(others.keySet(), NEW)) rows.add(new Row(shortName(name), others.get(name)));
            if (!rows.isEmpty()) byPackage.put(pkg, rows);
        }
        newMovies(context, byPackage);
        for (Map.Entry<String, List<Row>> entry : byPackage.entrySet()) {
            String pkg = entry.getKey();
            List<Row> rows = entry.getValue();
            StringBuilder log = new StringBuilder(pkg).append(':');
            for (Row r : rows) log.append(" [").append(r.title).append(' ').append(r.items.size()).append(']');
            Log.i("TvHome.Rows", log.toString());
        }
        return byPackage;
    }

    /**
     * intentUri prefix of a new film to be opened in another app: "search:<package>|<ivi link>". AppSearch finds the
     * title in that app and opens it there; the ivi link after the bar is the fallback.
     */
    static final String SEARCH_PREFIX = "search:";

    /**
     * config new_movies_apps ("package" or "package=direct"): those apps show only their history (Continue watching,
     * History) and New movies, the popular new films from ivi's catalogue (IviMovies; no app publishes new films).
     * "direct" opens them where they came from (ivi itself); otherwise they are looked up in that app.
     */
    private static void newMovies(Context context, Map<String, List<Row>> byPackage) {
        String[] apps = context.getResources().getStringArray(R.array.new_movies_apps);
        if (apps.length == 0) return;
        List<Program> movies = IviMovies.load();
        String continueTitle = context.getString(R.string.row_continue);
        for (String entry : apps) {
            String pkg = entry.replaceFirst("=.*$", "");
            boolean direct = entry.endsWith("=direct");
            boolean byTmdb = entry.endsWith("=tmdb");
            if (entry.endsWith("=num")) {
                // NUM's own list (NumReleases), so Home shows what NUM shows.
                List<Row> kept = new ArrayList<>();
                List<Row> rows = byPackage.get(pkg);
                if (rows != null) {
                    for (Row r : rows) {
                        if (r.title.equals(continueTitle) || HISTORY.matcher(r.title).find()) kept.add(r);
                    }
                }
                List<Program> own = NumReleases.load(context);
                if (!own.isEmpty()) kept.add(new Row(context.getString(R.string.row_new_movies), own));
                if (kept.isEmpty()) byPackage.remove(pkg);
                else byPackage.put(pkg, kept);
                continue;
            }
            List<Row> kept = new ArrayList<>();
            List<Row> rows = byPackage.get(pkg);
            if (rows != null) {
                for (Row r : rows) {
                    if (r.title.equals(continueTitle) || HISTORY.matcher(r.title).find()) kept.add(r);
                }
            }
            if (!movies.isEmpty()) {
                List<Program> items = new ArrayList<>();
                for (Program p : movies) {
                    if (byTmdb) {
                        // NUM opens a film by its TMDB page; films Wikidata has no TMDB id for are left out.
                        String id = IviMovies.tmdbId(p.title);
                        if (id == null) continue;
                        android.content.Intent page = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                Uri.parse("https://www.themoviedb.org/movie/" + id)).setComponent(
                                android.content.ComponentName.unflattenFromString(
                                        context.getString(R.string.search_num_activity)));
                        items.add(new Program(p.title, p.subtitle, p.poster,
                                page.toUri(android.content.Intent.URI_INTENT_SCHEME), p.description, p.meta, -1,
                                p.order, -1, "", p.wide));
                        continue;
                    }
                    items.add(direct ? p : new Program(p.title, p.subtitle, p.poster,
                            SEARCH_PREFIX + pkg + "|" + p.intentUri, p.description, p.meta, -1, p.order, -1, "", p.wide));
                }
                kept.add(new Row(context.getString(R.string.row_new_movies), items));
            }
            if (kept.isEmpty()) byPackage.remove(pkg);
            else byPackage.put(pkg, kept);
        }
    }

    /** 0 movies, 1 series, 2 animated series, 3 cartoons, or -1 when the genre text says nothing. */
    static int category(String genre) {
        if (genre.isEmpty()) return -1;
        String g = genre.toLowerCase(Locale.ROOT);
        boolean series = SEASON.matcher(genre).find() || g.contains("сериал") || g.contains("series");
        boolean animated = g.contains("мульт") || g.contains("анимац") || g.contains("animation") || g.contains("аниме");
        if (animated) return series ? 2 : 3;
        return series ? 1 : 0;
    }

    /** "LazyMediaDeluxe • История" -> "История": the app's name is on the shelf already. */
    private static String shortName(String channel) {
        int dot = channel.lastIndexOf('•');
        String name = dot >= 0 ? channel.substring(dot + 1).trim() : channel.trim();
        return name.isEmpty() ? channel : name;
    }

    private static final Pattern HISTORY = Pattern.compile("(?i)(истори|history)");
    private static final Pattern NEW = Pattern.compile("(?i)(новинк|новые|new)");

    /** The names in their order, those matching pattern moved to the front. */
    private static List<String> first(java.util.Collection<String> names, Pattern pattern) {
        List<String> front = new ArrayList<>(), rest = new ArrayList<>();
        for (String n : names) (pattern.matcher(n).find() ? front : rest).add(n);
        front.addAll(rest);
        return front;
    }

    private static List<String> union(Map<String, ?> a, Map<String, ?> b) {
        List<String> all = new ArrayList<>(a.keySet());
        for (String k : b.keySet()) if (!a.containsKey(k)) all.add(k);
        return all;
    }

    private static List<Program> sorted(List<Program> list) {
        List<Program> copy = new ArrayList<>(list);
        Collections.sort(copy, (x, y) -> Long.compare(y.order, x.order));
        return copy;
    }

    private static Map<Long, String> channels(Context context) {
        Map<Long, String> names = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            if (c == null) return names;
            int id = c.getColumnIndex(TvContract.Channels._ID);
            int name = c.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);
            while (c.moveToNext()) {
                if (id >= 0 && !c.isNull(id)) names.put(c.getLong(id), str(c, name));
            }
        } catch (RuntimeException e) {
            Log.w("TvHome.Rows", "Could not read channels", e);
        }
        return names;
    }

    private static void read(Context context, Uri uri, Map<String, List<Program>> out, boolean watchNext) {
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c == null) return;
            int pkg = c.getColumnIndex(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME);
            int title = c.getColumnIndex(TvContract.Programs.COLUMN_TITLE);
            int episode = c.getColumnIndex(TvContract.Programs.COLUMN_EPISODE_TITLE);
            int poster = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
            int thumb = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI);
            int intent = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_INTENT_URI);
            int position = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
            int duration = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS);
            int engaged = watchNext ? c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS) : -1;
            int weight = watchNext ? -1 : c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_WEIGHT);
            int browsable = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_BROWSABLE);
            int shortDesc = c.getColumnIndex(TvContract.Programs.COLUMN_SHORT_DESCRIPTION);
            int longDesc = c.getColumnIndex(TvContract.Programs.COLUMN_LONG_DESCRIPTION);
            int released = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_RELEASE_DATE);
            int rating = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_REVIEW_RATING);
            int season = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
            int episodeNo = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
            int channel = watchNext ? -1 : c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID);
            int genre = c.getColumnIndex("genre");
            while (c.moveToNext()) {
                String p = str(c, pkg), link = str(c, intent), name = str(c, title);
                if (p.isEmpty() || link.isEmpty() || name.isEmpty()) continue;
                if (browsable >= 0 && !c.isNull(browsable) && c.getInt(browsable) == 0) continue;
                String art = str(c, poster);
                if (art.isEmpty()) art = str(c, thumb);
                float progress = -1;
                if (watchNext && position >= 0 && duration >= 0 && c.getLong(duration) > 0)
                    progress = Math.min(1f, c.getLong(position) / (float) c.getLong(duration));
                long order = engaged >= 0 && !c.isNull(engaged) ? c.getLong(engaged)
                        : weight >= 0 && !c.isNull(weight) ? c.getLong(weight) : 0;
                String desc = str(c, longDesc);
                if (desc.isEmpty()) desc = str(c, shortDesc);
                StringBuilder meta = new StringBuilder();
                String year = str(c, released);
                if (year.length() >= 4) meta.append(year, 0, 4);
                String s = str(c, season), e = str(c, episodeNo);
                if (!s.isEmpty() && !e.isEmpty()) appendMeta(meta, "S" + s + " · E" + e);
                String r = str(c, rating);
                if (!r.isEmpty() && !r.equals("0") && !r.equals("0.0")) appendMeta(meta, "★ " + r);
                String g = str(c, genre);
                // NUM puts rating, season, genres and age in the genre column: show it as the meta line.
                if (meta.length() == 0 && !g.isEmpty()) meta.append(g);
                long ch = channel >= 0 && !c.isNull(channel) ? c.getLong(channel) : -1;
                out.computeIfAbsent(p, k -> new ArrayList<>()).add(new Program(name, str(c, episode), art, link, desc,
                        meta.toString(), progress, order, ch, g));
            }
        } catch (RuntimeException e) {
            Log.w("TvHome.Rows", "Could not read " + uri, e);
        }
    }

    private static void appendMeta(StringBuilder meta, String part) {
        if (meta.length() > 0) meta.append("  ·  ");
        meta.append(part);
    }

    private static String str(Cursor c, int column) {
        if (column < 0 || c.isNull(column)) return "";
        String s = c.getString(column);
        return s == null ? "" : s.trim();
    }
}
