package dev.leanhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Continue Watching from Trakt: cached first, so Home never waits. The token arrives as one file over ADB. */
final class Trakt {
    private static final String TAG = "PaixaoTV";
    private static final String CLIENT_ID = BuildConfig.TRAKT_CLIENT_ID;
    private static final String REDIRECT = "http://localhost:8765/callback";
    private static final String API = "https://api.trakt.tv";
    private static final long FRESH_MS = 5 * 60_000, RECOMMEND_MS = 6 * 3_600_000L;
    private static final long REFRESH_BEFORE_MS = 24 * 3_600_000L;
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Row {
        final String title;
        final List<Item> items;

        Row(String title, List<Item> items) {
            this.title = title;
            this.items = items;
        }
    }

    static final class Item {
        String title, detail;
        final String image, link;
        final int progress;
        String episode; // "/season/1/episode/4" for a Continue Watching episode.
        String label; // "T1 E4", kept apart from the episode's name so it can be translated.
        String tmdb; // "tv/123" or "movie/123", when Trakt gives the id.
        String pausedAt = "";
        long traktId, showTrakt, playbackId; // Set on Continue Watching cards, which the menu can change.

        Item(String title, String detail, String image, String link, int progress) {
            this.title = title;
            this.detail = detail;
            this.image = image;
            this.link = link;
            this.progress = progress;
        }

        Uri uri() {
            return Uri.parse(link);
        }
    }

    static void load(Context context, Consumer<List<Row>> done) {
        SharedPreferences store = context.getSharedPreferences("trakt", Context.MODE_PRIVATE);
        if (store.contains("playback")) done.accept(rows(context, store));
        long now = System.currentTimeMillis();
        boolean fresh = now - store.getLong("playback_at", 0) < FRESH_MS && store.contains("up_next")
                && store.contains("dropped");
        WORKER.execute(() -> {
            try {
                if (fresh) {
                    if (learn(context, store)) {
                        List<Row> rows = rows(context, store);
                        MAIN.post(() -> done.accept(rows));
                    }
                    return;
                }
                importToken(context, store);
                String token = token(store);
                if (token == null) return;
                SharedPreferences.Editor edit = store.edit();
                edit.putString("playback", request("GET", "/sync/playback?extended=images&limit=30", null, token))
                        .putString("up_next", request("GET", "/sync/progress/up_next?limit=20&extended=images", null, token))
                        .putLong("playback_at", now);
                if (now - store.getLong("recommended_at", 0) > RECOMMEND_MS || !store.contains("dropped")) {
                    String query = "?limit=10&extended=images&ignore_watched=true";
                    edit.putString("movies", request("GET", "/recommendations/movies" + query, null, token))
                            .putString("shows", request("GET", "/recommendations/shows" + query, null, token))
                            .putString("dropped", request("GET", "/users/hidden/dropped?limit=100", null, token))
                            .putLong("recommended_at", now);
                }
                edit.commit();
                learn(context, store);
                List<Row> rows = rows(context, store);
                MAIN.post(() -> done.accept(rows));
            } catch (Exception failed) {
                Log.w(TAG, "Trakt refresh failed: " + failed.getClass().getSimpleName()); // Keep showing the cached row.
            }
        });
    }

    /** Changes Trakt from the card's menu, then drops the caches so the next load shows Trakt's own answer. */
    static void change(Context context, Item item, boolean watched, Consumer<Boolean> done) {
        SharedPreferences store = context.getSharedPreferences("trakt", Context.MODE_PRIVATE);
        WORKER.execute(() -> {
            boolean saved = false;
            try {
                String token = token(store);
                JSONObject ids = new JSONObject().put("ids", new JSONObject().put("trakt", item.traktId));
                if (watched) {
                    String kind = item.episode != null ? "episodes" : "movies";
                    request("POST", "/sync/history", new JSONObject().put(kind, new JSONArray().put(ids)).toString(), token);
                } else if (item.showTrakt > 0) {
                    drop(item.showTrakt, token);
                }
                if (item.playbackId > 0) request("DELETE", "/sync/playback/" + item.playbackId, null, token);
                store.edit().putLong("playback_at", 0).putLong("recommended_at", 0).commit();
                saved = true;
            } catch (Exception failed) {
                Log.w(TAG, "Trakt change failed: " + failed);
            }
            boolean result = saved;
            MAIN.post(() -> done.accept(result));
        });
    }

    /** "dropped" is the site's Abandon. Its write call is undocumented here, so hiding the progress is the fallback. */
    private static void drop(long show, String token) throws Exception {
        String body = new JSONObject().put("shows", new JSONArray().put(
                new JSONObject().put("ids", new JSONObject().put("trakt", show)))).toString();
        try {
            request("POST", "/users/hidden/dropped", body, token);
        } catch (IOException notAccepted) {
            Log.w(TAG, "Dropped refused, hiding progress: " + notAccepted);
            request("POST", "/users/hidden/progress_watched", body, token);
        }
    }

    /** Fetches a card image on the worker, keeping a copy in the cache folder. */
    static void image(Context context, String url, int width, Consumer<Bitmap> done) {
        WORKER.execute(() -> {
            try {
                File file = new File(context.getCacheDir(), "t" + url.hashCode());
                if (!file.exists()) {
                    HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
                    http.setConnectTimeout(8_000);
                    http.setReadTimeout(12_000);
                    try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                        copy(in, out);
                    } finally {
                        http.disconnect();
                    }
                }
                BitmapFactory.Options size = new BitmapFactory.Options();
                size.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), size);
                size.inJustDecodeBounds = false;
                size.inSampleSize = Math.max(1, size.outWidth / width);
                size.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), size);
                if (bitmap != null) MAIN.post(() -> done.accept(bitmap));
            } catch (IOException failed) {
                Log.w(TAG, "Image failed: " + failed); // The card keeps its plain plate.
            }
        });
    }

    /** Trakt's own Up Next, as on its site, plus films in progress, which Up Next does not list. */
    private static List<Item> watching(SharedPreferences store) {
        List<Item> paused = parse(store.getString("playback", "[]"));
        Map<Long, Item> byId = new HashMap<>();
        for (Item item : paused) byId.put(item.traktId, item);
        List<Item> items = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(store.getString("up_next", "[]"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                JSONObject progress = row.optJSONObject("progress");
                JSONObject next = progress == null ? null : progress.optJSONObject("next_episode");
                if (next == null) continue;
                Item item = byId.get(next.getJSONObject("ids").optLong("trakt"));
                if (item == null) item = card(row.getJSONObject("show"), next, 0);
                if (item == null) continue;
                String watched = progress.optString("last_watched_at");
                if (watched.compareTo(item.pausedAt) > 0) item.pausedAt = watched;
                items.add(item);
            }
        } catch (Exception bad) {
            Log.w(TAG, "Bad Up Next row: " + bad);
        }
        for (Item item : paused) if (item.episode == null) items.add(item);
        return items;
    }

    /** One Continue Watching card. Null without an IMDb id, which Stremio's links need. */
    private static Item card(JSONObject media, JSONObject episode, int progress) throws Exception {
        JSONObject ids = media.getJSONObject("ids");
        String imdb = ids.optString("imdb");
        if (imdb.isEmpty()) return null;
        String detail = "Filme", video = imdb, label = null, episodePath = null;
        if (episode != null) {
            int season = episode.getInt("season"), number = episode.getInt("number");
            label = "T" + season + " E" + number;
            episodePath = "/season/" + season + "/episode/" + number;
            detail = label + " · " + episode.optString("title");
            video = imdb + ":" + season + ":" + number;
        }
        String link = "stremio:///detail/" + (episode != null ? "series/" : "movie/") + imdb + "/" + video
                + "?autoPlay=true";
        Item item = new Item(media.getString("title"), detail, image(media), link, progress);
        int tmdb = ids.optInt("tmdb");
        if (tmdb > 0) item.tmdb = (episode != null ? "tv/" : "movie/") + tmdb;
        item.label = label;
        item.episode = episodePath;
        item.traktId = (episode != null ? episode : media).getJSONObject("ids").optLong("trakt");
        if (episode != null) item.showTrakt = ids.optLong("trakt");
        return item;
    }

    private static List<Item> parse(String body) {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(body);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                boolean series = "episode".equals(row.getString("type"));
                Item item = card(row.getJSONObject(series ? "show" : "movie"),
                        series ? row.getJSONObject("episode") : null, (int) Math.round(row.getDouble("progress")));
                if (item == null) continue;
                item.pausedAt = row.optString("paused_at");
                item.playbackId = row.optLong("id");
                items.add(item);
            }
        } catch (Exception bad) {
            Log.w(TAG, "Bad Trakt row: " + bad);
        }
        return items;
    }

    private static boolean learn(Context context, SharedPreferences store) throws Exception {
        List<Item> playback = watching(store);
        boolean classified = Tmdb.classify(context, playback);
        Tmdb.linkNetflix(context, playback);
        List<Item> all = new ArrayList<>(playback);
        all.addAll(recommended(store));
        return Tmdb.translate(context, all) | classified;
    }

    private static List<Row> rows(Context context, SharedPreferences store) {
        List<Item> stremio = new ArrayList<>();
        for (Item item : watching(store)) {
            if (!Tmdb.onNetflix(context, item)) stremio.add(Tmdb.localize(context, item));
        }
        List<Item> recommended = new ArrayList<>();
        for (Item item : recommended(store)) recommended.add(Tmdb.localize(context, item));
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Continuar assistindo", stremio));
        rows.add(new Row("Recomendados para você", recommended));
        return rows;
    }

    /** Trakt does not say where a title was watched, so what Netflix Brazil carries counts as Netflix's. */
    static List<Item> netflixContinue(Context context) {
        List<Item> netflix = new ArrayList<>();
        SharedPreferences store = context.getSharedPreferences("trakt", Context.MODE_PRIVATE);
        for (Item item : watching(store)) {
            if (!Tmdb.onNetflix(context, item)) continue;
            Tmdb.localize(context, item);
            Item card = new Item(item.title, item.detail, item.image, "netflix:" + item.tmdb, item.progress);
            card.tmdb = item.tmdb;
            card.episode = item.episode;
            card.traktId = item.traktId;
            card.showTrakt = item.showTrakt;
            card.playbackId = item.playbackId;
            netflix.add(card);
        }
        return netflix;
    }

    /** Films and series taken in turns. */
    private static List<Item> recommended(SharedPreferences store) {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray movies = new JSONArray(store.getString("movies", "[]"));
            JSONArray shows = new JSONArray(store.getString("shows", "[]"));
            Set<Long> dropped = new HashSet<>();
            JSONArray hidden = new JSONArray(store.getString("dropped", "[]"));
            for (int i = 0; i < hidden.length(); i++) {
                JSONObject show = hidden.getJSONObject(i).optJSONObject("show");
                if (show != null) dropped.add(show.getJSONObject("ids").optLong("trakt"));
            }
            for (int i = 0; i < Math.max(movies.length(), shows.length()); i++) {
                if (i < movies.length()) addRecommended(items, movies.getJSONObject(i), "movie", "Filme");
                if (i < shows.length() && !dropped.contains(shows.getJSONObject(i).getJSONObject("ids").optLong("trakt"))) {
                    addRecommended(items, shows.getJSONObject(i), "series", "Série");
                }
            }
        } catch (Exception bad) {
            Log.w(TAG, "Bad Trakt recommendation: " + bad);
        }
        return items;
    }

    private static void addRecommended(List<Item> items, JSONObject media, String type, String label) {
        String imdb = media.optJSONObject("ids") == null ? "" : media.optJSONObject("ids").optString("imdb");
        if (imdb.isEmpty()) return;
        int year = media.optInt("year");
        String link = "stremio:///detail/" + type + "/" + imdb + ("movie".equals(type) ? "/" + imdb : "");
        Item item = new Item(media.optString("title"), label + (year > 0 ? " · " + year : ""), image(media), link, -1);
        int tmdb = media.optJSONObject("ids").optInt("tmdb");
        if (tmdb > 0) item.tmdb = ("movie".equals(type) ? "movie/" : "tv/") + tmdb;
        items.add(item);
    }

    /** A plain GET for feeds that sign the URL themselves. */
    static String read(String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        try {
            http.setConnectTimeout(8_000);
            http.setReadTimeout(12_000);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                copy(in, bytes);
            }
            return bytes.toString("UTF-8");
        } finally {
            http.disconnect();
        }
    }

    private static String image(JSONObject media) {
        JSONObject images = media.optJSONObject("images");
        if (images == null) return null;
        for (String kind : new String[] {"thumb", "fanart", "poster"}) {
            JSONArray urls = images.optJSONArray(kind);
            if (urls != null && urls.length() > 0) return "https://" + urls.optString(0);
        }
        return null;
    }

    /** One file left by the installer in this app's own folder: read it once, then delete it. */
    private static void importToken(Context context, SharedPreferences store) throws Exception {
        File file = new File(context.getExternalFilesDir(null), "trakt-token.json");
        if (!file.exists()) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            copy(in, bytes);
        }
        save(store, new JSONObject(bytes.toString("UTF-8")));
        file.delete();
    }

    private static String token(SharedPreferences store) throws Exception {
        String access = store.getString("access", null);
        if (access == null || store.getLong("expires", 0) - System.currentTimeMillis() > REFRESH_BEFORE_MS) {
            return access;
        }
        JSONObject refresh = new JSONObject().put("refresh_token", store.getString("refresh", ""))
                .put("client_id", CLIENT_ID).put("redirect_uri", REDIRECT).put("grant_type", "refresh_token");
        save(store, new JSONObject(request("POST", "/oauth/token", refresh.toString(), null)));
        return store.getString("access", null);
    }

    private static void save(SharedPreferences store, JSONObject token) throws Exception {
        store.edit().putString("access", token.getString("access_token"))
                .putString("refresh", token.getString("refresh_token"))
                .putLong("expires", (token.getLong("created_at") + token.getLong("expires_in")) * 1000).commit();
    }

    private static String request(String method, String path, String body, String token) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            http.setConnectTimeout(8_000);
            http.setReadTimeout(12_000);
            http.setRequestMethod(method);
            http.setRequestProperty("Content-Type", "application/json");
            http.setRequestProperty("User-Agent", "PaixaoTV/0.1");
            http.setRequestProperty("trakt-api-version", "2");
            http.setRequestProperty("trakt-api-key", CLIENT_ID);
            if (token != null) http.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                http.setDoOutput(true);
                try (OutputStream out = http.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                copy(in, bytes);
            }
            return bytes.toString("UTF-8");
        } finally {
            http.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] chunk = new byte[8192];
        for (int read; (read = in.read(chunk)) > 0; ) out.write(chunk, 0, read);
    }
}
