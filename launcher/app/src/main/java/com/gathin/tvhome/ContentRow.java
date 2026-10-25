package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The row of Continue watching and recommended programs under the top shelf's name, for the focused dock app
 * (config.xml content_row). Up from the dock enters it, Up again goes to the top bar, Down goes back to the dock.
 * The focused program is handed to the host for the backdrop and description. Data from TvRows; posters load over
 * the network only while the row shows them.
 */
final class ContentRow {
    interface Host {
        void onRowUp();
        void onRowDown();
        /** A program took focus; backdrop is its large poster once loaded, null until then. */
        void onProgramFocused(TvRows.Program program, Bitmap backdrop);
    }

    private static final int MAX_ITEMS = 30;
    private static final int CARD_W_DP = 150;
    /** Mirrors of TMDB's image server, tried in turn when an app's own proxy for /t/p/ paths is down. */
    private static final String[] TMDB_MIRRORS = {"https://imagetmdb.com", "https://image.tmdb.org"};

    private final Activity activity;
    private final RecyclerView list;
    private final Host host;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newFixedThreadPool(3);
    private final ExecutorService backdropLoader = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final LruCache<String, Bitmap> backdrops = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Set<String> loading = new HashSet<>();
    private final TextView label;
    private volatile Map<String, List<TvRows.Row>> data = Collections.emptyMap();
    /** The focused dock app's rows, the one on screen, and its programs. Up shows the next row, Down the previous. */
    private List<TvRows.Row> rows = Collections.emptyList();
    private int rowIndex;
    private List<TvRows.Program> shown = Collections.emptyList();
    private String pkg;
    private int lastFocused;
    /** The program that has focus now, or null while focus is outside the row. */
    private TvRows.Program focused;

    ContentRow(Activity activity, RecyclerView list, TextView label, Host host) {
        this.activity = activity;
        this.list = list;
        this.label = label;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setPadding(0, dp(8), dp(24), dp(8));
        list.setAdapter(new Adapter());
        list.setVisibility(View.VISIBLE);
    }

    /** Reads the TV provider again (off the main thread) and refreshes the row on screen. */
    void refresh() {
        new Thread(() -> {
            Map<String, List<TvRows.Row>> fresh = TvRows.load(activity.getApplicationContext());
            main.post(() -> {
                data = fresh;
                String current = pkg;
                pkg = null;
                show(current);
            });
        }, "tvrows").start();
    }

    /** Shows the first row of this package, or nothing. */
    void show(String packageName) {
        if (packageName != null && packageName.equals(pkg)) return;
        pkg = packageName;
        List<TvRows.Row> all = packageName == null ? null : data.get(packageName);
        rows = all == null ? Collections.emptyList() : all;
        rowIndex = 0;
        showRow();
    }

    private void showRow() {
        List<TvRows.Program> items = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        if (rowIndex < rows.size()) {
            for (TvRows.Program p : rows.get(rowIndex).items) {
                if (items.size() >= MAX_ITEMS) break;
                if (titles.add(p.title + "\n" + p.subtitle)) items.add(p);
            }
        }
        shown = items;
        lastFocused = 0;
        list.getAdapter().notifyDataSetChanged();
        list.scrollToPosition(0);
        list.setAlpha(items.isEmpty() ? 0f : 1f);
        list.setFocusable(false);
        // "СЕРИАЛЫ   ▲ МУЛЬТСЕРИАЛЫ": this row, and the one Up brings.
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
        if (rowIndex < rows.size()) {
            text.append(rows.get(rowIndex).title);
            if (rowIndex + 1 < rows.size()) {
                int start = text.length();
                text.append("     ▲  ").append(rows.get(rowIndex + 1).title);
                text.setSpan(new android.text.style.ForegroundColorSpan(0x66FFFFFF), start, text.length(), 0);
            }
            if (rows.size() > 1) {
                int start = text.length();
                text.append("     ").append(String.valueOf(rowIndex + 1)).append('/').append(String.valueOf(rows.size()));
                text.setSpan(new android.text.style.ForegroundColorSpan(0x4DFFFFFF), start, text.length(), 0);
            }
        }
        label.setText(text);
    }

    /** Up or Down inside the rows: show the neighbouring row with focus on its first program. */
    private void switchRow(int to) {
        rowIndex = to;
        showRow();
        list.post(() -> {
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(0);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    /** Moves focus into the row; false when this app has nothing to show. */
    boolean focus() {
        if (shown.isEmpty()) return false;
        int pos = Math.min(lastFocused, shown.size() - 1);
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(pos);
        if (vh == null) {
            list.scrollToPosition(pos);
            list.post(() -> {
                RecyclerView.ViewHolder later = list.findViewHolderForAdapterPosition(pos);
                if (later != null) later.itemView.requestFocus();
            });
            return true;
        }
        vh.itemView.requestFocus();
        return true;
    }

    void release() {
        loader.shutdownNow();
        backdropLoader.shutdownNow();
    }

    private void open(TvRows.Program p) {
        if (AppSearch.isBorrowed(p)) {
            AppSearch.open(activity, p);
            return;
        }
        Intent intent;
        try {
            intent = Intent.parseUri(p.intentUri, Intent.URI_INTENT_SCHEME);
        } catch (Exception e) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(p.intentUri));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (activity.getPackageManager().resolveActivity(intent, 0) == null) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, p.title), Toast.LENGTH_SHORT).show();
            return;
        }
        AppVpn.start(activity, pkg, intent);
    }

    private void onFocused(TvRows.Program given) {
        focused = given;
        // ivi's Continue watching has no description: take the series' from ivi's catalogue.
        if (given.description.isEmpty() && IviInfo.PACKAGE.equals(pkg)) {
            IviInfo.Info info = IviInfo.cached(given.title);
            if (info != null) {
                given = withInfo(given, info);
                focused = given;
            } else {
                TvRows.Program asked = given;
                IviInfo.fetch(given.title, answer -> {
                    if (focused == asked && !answer.description.isEmpty()) onFocused(withInfo(asked, answer));
                });
            }
        }
        TvRows.Program p = given;
        // A borrowed title is looked up once focus rests on it, so OK usually finds the answer ready.
        if (AppSearch.isBorrowed(p)) {
            main.postDelayed(() -> { if (focused == p) AppSearch.prefetch(activity, p); }, 800);
        }
        Bitmap big = p.poster.isEmpty() ? null : backdrops.get(p.poster);
        host.onProgramFocused(p, big);
        if (big != null || p.poster.isEmpty()) return;
        String uri = p.poster;
        backdropLoader.execute(() -> {
            if (focused != p) return;   // moved on before this one started
            Bitmap bmp = decode(uri, 1280);
            main.post(() -> {
                if (bmp == null) return;
                backdrops.put(uri, bmp);
                if (focused == p) host.onProgramFocused(p, bmp);
            });
        });
    }

    private static TvRows.Program withInfo(TvRows.Program p, IviInfo.Info info) {
        if (info.description.isEmpty() && info.meta.isEmpty()) return p;
        return new TvRows.Program(p.title, p.subtitle, p.poster, p.intentUri, info.description,
                p.meta.isEmpty() ? info.meta : p.meta, p.progress, p.order, p.channelId, p.genre);
    }

    // ---- views -----------------------------------------------------------------------------------

    private int dp(float v) { return Math.round(v * density); }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView poster, posterBack;
        final TextView title, fallback;
        final View progressTrack, progress;

        Holder(LinearLayout item, ImageView posterBack, ImageView poster, TextView fallback, View track,
               View progress, TextView title) {
            super(item);
            this.posterBack = posterBack;
            this.poster = poster;
            this.fallback = fallback;
            this.progressTrack = track;
            this.progress = progress;
            this.title = title;
        }

        /**
         * Every card is landscape. Landscape art fills it; a portrait poster fills its width and shows its upper part,
         * where posters keep the faces and the title.
         */
        void setPoster(Bitmap bmp) {
            poster.setImageBitmap(bmp);
            posterBack.setImageBitmap(null);
            fallback.setVisibility(bmp == null ? View.VISIBLE : View.INVISIBLE);
            if (bmp == null || bmp.getHeight() <= bmp.getWidth()) {
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                return;
            }
            float w = dp(CARD_W_DP), h = dp(CARD_W_DP * 9 / 16f);
            float scale = w / bmp.getWidth();
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(scale, scale);
            m.postTranslate(0, -(bmp.getHeight() * scale - h) * 0.22f);
            poster.setScaleType(ImageView.ScaleType.MATRIX);
            poster.setImageMatrix(m);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int w = dp(CARD_W_DP), h = dp(CARD_W_DP * 9 / 16f);
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setFocusable(true);
            item.setClipChildren(false);
            RecyclerView.LayoutParams ilp = new RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.rightMargin = dp(16);
            item.setLayoutParams(ilp);

            FrameLayout frame = new FrameLayout(activity);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1E1E22);
            bg.setCornerRadius(dp(10));
            frame.setBackground(bg);
            frame.setClipToOutline(true);
            frame.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
                }
            });
            item.addView(frame, new LinearLayout.LayoutParams(w, h));

            TextView fallback = new TextView(activity);
            fallback.setTextColor(0xCCFFFFFF);
            fallback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(dp(8), dp(8), dp(8), dp(8));
            fallback.setMaxLines(3);
            fallback.setEllipsize(TextUtils.TruncateAt.END);
            frame.addView(fallback, new FrameLayout.LayoutParams(-1, -1));

            ImageView posterBack = new ImageView(activity);
            posterBack.setScaleType(ImageView.ScaleType.CENTER_CROP);
            posterBack.setAlpha(0.3f);
            frame.addView(posterBack, new FrameLayout.LayoutParams(-1, -1));

            ImageView poster = new ImageView(activity);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(poster, new FrameLayout.LayoutParams(-1, -1));

            View track = new View(activity);
            track.setBackgroundColor(0x66000000);
            frame.addView(track, new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM));
            View progress = new View(activity);
            progress.setBackgroundColor(0xFFFF3B30);
            frame.addView(progress, new FrameLayout.LayoutParams(0, dp(4), Gravity.BOTTOM | Gravity.START));

            TextView title = new TextView(activity);
            title.setTextColor(0x99FFFFFF);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            item.addView(title, lp);

            Holder holder = new Holder(item, posterBack, poster, fallback, track, progress, title);
            item.setOnFocusChangeListener((v, hasFocus) -> {
                float s = hasFocus ? 1.08f : 1f;
                frame.animate().scaleX(s).scaleY(s).setDuration(140).start();
                title.setTextColor(hasFocus ? Color.WHITE : 0x99FFFFFF);
                GradientDrawable d = (GradientDrawable) frame.getBackground();
                d.setStroke(hasFocus ? dp(2) : 0, Color.WHITE);
                int pos = holder.getBindingAdapterPosition();
                if (hasFocus && pos >= 0 && pos < shown.size()) {
                    lastFocused = pos;
                    onFocused(shown.get(pos));
                } else if (!hasFocus && !list.hasFocus()) {
                    focused = null;
                }
            });
            item.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos >= 0 && pos < shown.size()) open(shown.get(pos));
            });
            item.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = holder.getBindingAdapterPosition();
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (rowIndex + 1 < rows.size()) switchRow(rowIndex + 1);
                        else { focused = null; host.onRowUp(); }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (rowIndex > 0) switchRow(rowIndex - 1);
                        else { focused = null; host.onRowDown(); }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT: return pos <= 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: return pos >= shown.size() - 1;
                    default: return false;
                }
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            TvRows.Program p = shown.get(position);
            h.title.setText(p.subtitle.isEmpty() ? p.title : p.title + " · " + p.subtitle);
            h.fallback.setText(p.title);
            boolean hasProgress = p.progress > 0f;
            h.progressTrack.setVisibility(hasProgress ? View.VISIBLE : View.GONE);
            h.progress.setVisibility(hasProgress ? View.VISIBLE : View.GONE);
            if (hasProgress) {
                ViewGroup.LayoutParams lp = h.progress.getLayoutParams();
                lp.width = Math.round(dp(CARD_W_DP) * p.progress);
                h.progress.setLayoutParams(lp);
            }
            h.poster.setTag(p.poster);
            Bitmap cached = p.poster.isEmpty() ? null : posters.get(p.poster);
            h.setPoster(cached);
            if (cached == null && !p.poster.isEmpty()) load(p.poster);
        }

        @Override public int getItemCount() { return shown.size(); }
    }

    // ---- posters ---------------------------------------------------------------------------------

    private void load(String uri) {
        if (!loading.add(uri)) return;
        loader.execute(() -> {
            Bitmap bmp = decode(uri, 300);
            main.post(() -> {
                loading.remove(uri);
                if (bmp == null) return;
                posters.put(uri, bmp);
                for (int i = 0; i < list.getChildCount(); i++) {
                    RecyclerView.ViewHolder vh = list.getChildViewHolder(list.getChildAt(i));
                    if (vh instanceof Holder && uri.equals(((Holder) vh).poster.getTag())) ((Holder) vh).setPoster(bmp);
                }
            });
        });
    }

    /** The image at uri, downsampled to about targetPx wide; TMDB paths fall back to public mirrors. */
    private Bitmap decode(String uri, int targetPx) {
        byte[] bytes = fetch(uri);
        int tmdb = uri.indexOf("/t/p/");
        if (bytes == null && tmdb > 0 && uri.startsWith("http")) {
            // Size segment w1280 -> w780: plenty for a TV and a third of the bytes.
            String path = uri.substring(tmdb).replaceFirst("^/t/p/[^/]+/", "/t/p/w780/");
            for (String mirror : TMDB_MIRRORS) {
                if ((bytes = fetch(mirror + path)) != null) break;
            }
        }
        if (bytes == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (bmp == null) Log.w("TvHome.Rows", "Poster not decodable: " + uri + " (" + bytes.length + " bytes)");
            return bmp;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private byte[] fetch(String uri) {
        try {
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                HttpURLConnection http = (HttpURLConnection) new URL(uri).openConnection();
                http.setConnectTimeout(8000);
                http.setReadTimeout(8000);
                try (InputStream in = http.getInputStream()) { return readAll(in); }
                finally { http.disconnect(); }
            }
            try (InputStream in = activity.getContentResolver().openInputStream(Uri.parse(uri))) {
                return in == null ? null : readAll(in);
            }
        } catch (Exception e) {
            Log.w("TvHome.Rows", "Poster failed: " + uri + " " + e);
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > 6 * 1024 * 1024) throw new java.io.IOException("poster too large");
        }
        return out.toByteArray();
    }
}
