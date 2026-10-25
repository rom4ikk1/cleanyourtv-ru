package com.gathin.tvhome;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Opens a new film (TvRows.SEARCH_PREFIX) in the app whose row it sits in.
 *
 * LazyMedia: its search provider (search_lmd_authority, the one Android TV's global search uses) is asked for the
 * title, and only the same title opens, in its article screen, the way global search opens it. It searches its online
 * sources and takes about half a minute, so a title is looked up once focus rests on it, and every answer is kept.
 * NUM: the title goes to its details screen with ACTION_SEARCH. Not found, or another app: the ivi link opens.
 */
final class AppSearch {
    static final String LMD = "com.lazycatsoftware.lmd";
    static final String NUM = "ru.yourok.num";

    private static final long WAIT_S = 75;
    private static final String PREFS = "app_search_exact";
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Map<String, Future<String>> PENDING = new ConcurrentHashMap<>();
    private static final Set<String> NOT_FOUND = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppSearch() { }

    static boolean isBorrowed(TvRows.Program p) {
        return p.intentUri.startsWith(TvRows.SEARCH_PREFIX);
    }

    private static String target(TvRows.Program p) {
        String rest = p.intentUri.substring(TvRows.SEARCH_PREFIX.length());
        int bar = rest.indexOf('|');
        return bar > 0 ? rest.substring(0, bar) : "";
    }

    private static String fallback(TvRows.Program p) {
        String rest = p.intentUri.substring(TvRows.SEARCH_PREFIX.length());
        int bar = rest.indexOf('|');
        return bar >= 0 ? rest.substring(bar + 1) : rest;
    }

    /** Starts looking the title up in LazyMedia, if it is a LazyMedia row and no answer is kept yet. */
    static void prefetch(Context ctx, TvRows.Program p) {
        if (!LMD.equals(target(p))) return;
        if (cached(ctx, p.title) == null && !NOT_FOUND.contains(p.title)) future(ctx.getApplicationContext(), p.title);
    }

    static void open(Activity activity, TvRows.Program p) {
        Context app = activity.getApplicationContext();
        String target = target(p);
        if (NUM.equals(target)) {
            ComponentName details = ComponentName.unflattenFromString(app.getString(R.string.search_num_activity));
            Intent search = new Intent(Intent.ACTION_SEARCH).setComponent(details)
                    .putExtra(SearchManager.QUERY, p.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(search);
                return;
            } catch (RuntimeException e) {
                Log.w("TvHome.Search", "NUM could not search " + p.title, e);
            }
            openFallback(activity, p, target);
            return;
        }
        if (!LMD.equals(target)) {
            openFallback(activity, p, target);
            return;
        }
        String kept = cached(app, p.title);
        if (kept != null || NOT_FOUND.contains(p.title)) {
            launchLmd(activity, p, kept);
            return;
        }
        Toast.makeText(app, app.getString(R.string.search_finding, p.title, label(app, target)), Toast.LENGTH_LONG).show();
        Future<String> answer = future(app, p.title);
        new Thread(() -> {
            String data = null;
            try { data = answer.get(WAIT_S, TimeUnit.SECONDS); }
            catch (Exception e) { Log.w("TvHome.Search", "No answer for " + p.title, e); }
            String found = data;
            MAIN.post(() -> launchLmd(activity, p, found));
        }, "app-search-open").start();
    }

    private static void launchLmd(Activity activity, TvRows.Program p, String data) {
        Context app = activity.getApplicationContext();
        if (data != null && !data.isEmpty()) {
            ComponentName target = ComponentName.unflattenFromString(app.getString(R.string.search_lmd_activity));
            Intent open = new Intent(Intent.ACTION_SEARCH).setComponent(target).setData(Uri.parse(data))
                    .putExtra(SearchManager.QUERY, p.title).putExtra(SearchManager.USER_QUERY, p.title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(open);
                return;
            } catch (RuntimeException e) {
                Log.w("TvHome.Search", "Could not open " + p.title, e);
            }
        }
        Toast.makeText(app, app.getString(R.string.search_not_found, label(app, LMD)), Toast.LENGTH_SHORT).show();
        openFallback(activity, p, LMD);
    }

    private static void openFallback(Activity activity, TvRows.Program p, String target) {
        try {
            Intent fallback = Intent.parseUri(fallback(p), Intent.URI_INTENT_SCHEME);
            activity.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, p.title), Toast.LENGTH_SHORT).show();
        }
    }

    private static Future<String> future(Context app, String title) {
        return PENDING.computeIfAbsent(title, t -> POOL.submit(() -> {
            try {
                return resolve(app, t);
            } finally {
                PENDING.remove(t);
            }
        }));
    }

    private static String cached(Context ctx, String title) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(title, null);
    }

    /** The intent data of LazyMedia's entry with this very title, kept for next time; null when it has none. */
    private static String resolve(Context app, String title) {
        Uri uri = Uri.parse("content://" + app.getString(R.string.search_lmd_authority) + "/"
                + SearchManager.SUGGEST_URI_PATH_QUERY + "/" + Uri.encode(title));
        String best = null;
        try (Cursor c = app.getContentResolver().query(uri, null, null, new String[]{title}, null)) {
            if (c == null) return null;
            int text = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1);
            int data = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA);
            if (data < 0 || text < 0) return null;
            // Only the same title counts: "Фонари" (a 2026 series) must not open "Зеленый фонарь" (a 2011 film).
            String wanted = normalize(title);
            while (c.moveToNext()) {
                String d = c.getString(data);
                if (d != null && !d.isEmpty() && normalize(c.getString(text)).equals(wanted)) { best = d; break; }
            }
        } catch (RuntimeException e) {
            Log.w("TvHome.Search", "Search failed for " + title, e);
            return null;
        }
        if (best != null) {
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString(title, best).apply();
        } else {
            NOT_FOUND.add(title);   // until the launcher restarts: the app may add the title later
        }
        Log.i("TvHome.Search", title + (best != null ? " found" : " not found"));
        return best;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String label(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return "";
        }
    }
}
