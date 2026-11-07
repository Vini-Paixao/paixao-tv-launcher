package dev.leanhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** What is popular on each streaming service in Brazil. TMDB has no title links for them, so OK opens their search. */
final class Tmdb {
    private static final String TAG = "PaixaoTV";
    private static final long FRESH_MS = 6 * 3_600_000L;
    private static final String[][] SERVICES = {
            {"com.netflix.ninja", "na Netflix", "8", ""},
            {"com.amazon.firebat", "no Prime Video", "119", "https://app.primevideo.com/search?phrase="}};
    // ponytail: Trakt has no source field, so anything paused before Netflix was linked counts as Stremio.
    // A title also on Netflix but watched in Stremio after this date still lands on Netflix's row.
    private static final String NETFLIX_LINKED = BuildConfig.NETFLIX_LINKED_AT;
    // Names everyone here knows in the original. There is no rule for this, so it is a list.
    private static final Set<String> KEEP_ORIGINAL_TITLE = new HashSet<>(Arrays.asList("tv/34524"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void load(Context context, Consumer<Map<String, List<Trakt.Row>>> done) {
        SharedPreferences store = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE);
        if (store.contains("movie_8")) done.accept(rows(store));
        boolean fresh = System.currentTimeMillis() - store.getLong("at", 0) < FRESH_MS;
        Trakt.WORKER.execute(() -> {
            try {
                if (fresh) {
                    linkNetflix(context, popularOnNetflix(store));
                    return;
                }
                importKey(context, store);
                String key = store.getString("key", null);
                if (key == null) return;
                SharedPreferences.Editor edit = store.edit();
                for (String[] service : SERVICES) {
                    for (String kind : new String[] {"movie", "tv"}) {
                        edit.putString(kind + "_" + service[2], Trakt.read("https://api.themoviedb.org/3/discover/"
                                + kind + "?language=pt-BR&watch_region=BR&sort_by=popularity.desc&vote_count.gte=200"
                                + "&with_watch_monetization_types=flatrate&with_watch_providers=" + service[2]
                                + "&api_key=" + key));
                    }
                }
                edit.putLong("at", System.currentTimeMillis()).commit();
                Map<String, List<Trakt.Row>> rows = rows(store);
                MAIN.post(() -> done.accept(rows));
                linkNetflix(context, popularOnNetflix(store));
            } catch (Exception failed) {
                Log.w(TAG, "TMDB refresh failed: " + failed.getClass().getSimpleName()); // The URL holds the key.
            }
        });
    }

    static boolean onNetflix(Context context, Trakt.Item item) {
        return item.tmdb != null && item.pausedAt.compareTo(NETFLIX_LINKED) >= 0
                && context.getSharedPreferences("tmdb", Context.MODE_PRIVATE).getBoolean("netflix_" + item.tmdb, false);
    }

    private static List<Trakt.Item> popularOnNetflix(SharedPreferences store) {
        List<Trakt.Item> items = items(store, "movie_8", "");
        items.addAll(items(store, "tv_8", ""));
        return items;
    }

    /** Netflix's own id for a title, kept from Wikidata; null when nobody has recorded one. */
    static String netflixId(Context context, Trakt.Item item) {
        String id = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE).getString("nflx_" + item.tmdb, "");
        return id.isEmpty() ? null : id;
    }

    /** One Wikidata query per kind, for the titles not asked about yet. P4983 and P4947 are TMDB's series and film ids. */
    static void linkNetflix(Context context, List<Trakt.Item> items) throws Exception {
        SharedPreferences store = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE);
        for (String[] kind : new String[][] {{"tv/", "P4983"}, {"movie/", "P4947"}}) {
            StringBuilder ids = new StringBuilder();
            List<String> asked = new ArrayList<>();
            for (Trakt.Item item : items) {
                if (item.tmdb == null || !item.tmdb.startsWith(kind[0]) || store.contains("nflx_" + item.tmdb)) continue;
                String id = item.tmdb.substring(kind[0].length());
                if (asked.contains(id)) continue;
                asked.add(id);
                ids.append('"').append(id).append("\" ");
            }
            if (asked.isEmpty()) continue;
            String query = "SELECT ?tmdb ?netflix WHERE { VALUES ?tmdb { " + ids + "} ?item wdt:" + kind[1]
                    + " ?tmdb. ?item wdt:P1874 ?netflix. }";
            JSONArray rows = new JSONObject(Trakt.read("https://query.wikidata.org/sparql?format=json&query="
                    + Uri.encode(query))).getJSONObject("results").getJSONArray("bindings");
            SharedPreferences.Editor edit = store.edit();
            for (String id : asked) edit.putString("nflx_" + kind[0] + id, "");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                edit.putString("nflx_" + kind[0] + row.getJSONObject("tmdb").getString("value"),
                        row.getJSONObject("netflix").getString("value"));
            }
            edit.commit();
        }
    }

    /** Asks once per title whether Netflix Brazil streams it, on Trakt's worker. True when it learned something. */
    static boolean classify(Context context, List<Trakt.Item> items) throws Exception {
        SharedPreferences store = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE);
        importKey(context, store);
        String key = store.getString("key", null);
        if (key == null) return false;
        boolean learned = false;
        for (Trakt.Item item : items) {
            if (item.tmdb == null || store.contains("netflix_" + item.tmdb)) continue;
            JSONObject brazil = new JSONObject(Trakt.read("https://api.themoviedb.org/3/" + item.tmdb
                    + "/watch/providers?api_key=" + key)).getJSONObject("results").optJSONObject("BR");
            JSONArray streaming = brazil == null ? null : brazil.optJSONArray("flatrate");
            boolean netflix = false;
            for (int i = 0; streaming != null && i < streaming.length(); i++) {
                netflix |= streaming.getJSONObject(i).optInt("provider_id") == 8;
            }
            store.edit().putBoolean("netflix_" + item.tmdb, netflix).commit();
            learned = true;
        }
        return learned;
    }

    /** Fetches Portuguese names once per title and episode. True when it learned something. */
    static boolean translate(Context context, List<Trakt.Item> items) throws Exception {
        SharedPreferences store = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE);
        String key = store.getString("key", null);
        if (key == null) return false;
        boolean learned = false;
        for (Trakt.Item item : items) {
            if (item.tmdb == null) continue;
            if (!store.contains("pt_" + item.tmdb)) {
                JSONObject media = new JSONObject(Trakt.read("https://api.themoviedb.org/3/" + item.tmdb
                        + "?language=pt-BR&api_key=" + key));
                store.edit().putString("pt_" + item.tmdb, media.optString("title", media.optString("name"))).commit();
                learned = true;
            }
            if (item.episode != null && !store.contains("pt_" + item.tmdb + item.episode)) {
                JSONObject episode = new JSONObject(Trakt.read("https://api.themoviedb.org/3/" + item.tmdb
                        + item.episode + "?language=pt-BR&api_key=" + key));
                store.edit().putString("pt_" + item.tmdb + item.episode, episode.optString("name")).commit();
                learned = true;
            }
        }
        return learned;
    }

    /** Swaps in the Portuguese names when known; TMDB's "Episódio 4" placeholder keeps the real title. */
    static Trakt.Item localize(Context context, Trakt.Item item) {
        if (item.tmdb == null) return item;
        SharedPreferences store = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE);
        String title = store.getString("pt_" + item.tmdb, "");
        if (!title.isEmpty() && !KEEP_ORIGINAL_TITLE.contains(item.tmdb)) item.title = title;
        if (item.episode != null) {
            String name = store.getString("pt_" + item.tmdb + item.episode, "");
            if (!name.isEmpty() && !name.startsWith("Episódio ")) item.detail = item.label + " · " + name;
        }
        return item;
    }

    /** Films and series in turns, for a single row. */
    static Trakt.Row mix(String title, List<Trakt.Row> rows) {
        List<Trakt.Item> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            for (Trakt.Row row : rows) if (i < row.items.size()) items.add(row.items.get(i));
        }
        return new Trakt.Row(title, items);
    }

    private static Map<String, List<Trakt.Row>> rows(SharedPreferences store) {
        Map<String, List<Trakt.Row>> all = new HashMap<>();
        for (String[] service : SERVICES) {
            List<Trakt.Row> rows = new ArrayList<>();
            rows.add(new Trakt.Row("Filmes em alta no " + service[1], items(store, "movie_" + service[2], service[3])));
            rows.add(new Trakt.Row("Séries em alta no " + service[1], items(store, "tv_" + service[2], service[3])));
            all.put(service[0], rows);
        }
        return all;
    }

    private static List<Trakt.Item> items(SharedPreferences store, String name, String search) {
        List<Trakt.Item> items = new ArrayList<>();
        try {
            JSONArray rows = new JSONObject(store.getString(name, "{}")).optJSONArray("results");
            for (int i = 0; rows != null && i < rows.length() && items.size() < 12; i++) {
                JSONObject row = rows.getJSONObject(i);
                String image = row.optString("backdrop_path");
                String title = row.optString("title", row.optString("name"));
                if (image.isEmpty() || "null".equals(image) || title.isEmpty()) continue;
                String date = row.optString("release_date", row.optString("first_air_date"));
                String tmdb = (name.startsWith("tv") ? "tv/" : "movie/") + row.optInt("id");
                Trakt.Item item = new Trakt.Item(title, date.length() >= 4 ? date.substring(0, 4) : "",
                        "https://image.tmdb.org/t/p/w300" + image,
                        search.isEmpty() ? "netflix:" + tmdb : search + Uri.encode(title), -1);
                item.tmdb = tmdb;
                items.add(item);
            }
        } catch (Exception bad) {
            Log.w(TAG, "Bad TMDB row: " + bad);
        }
        return items;
    }

    /** One file left by the installer in this app's own folder: read it once, then delete it. */
    private static void importKey(Context context, SharedPreferences store) throws Exception {
        File file = new File(context.getExternalFilesDir(null), "tmdb.txt");
        if (!file.exists()) return;
        String[] words = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim().split("[\\s=]+");
        store.edit().putString("key", words[words.length - 1]).commit();
        file.delete();
    }
}
