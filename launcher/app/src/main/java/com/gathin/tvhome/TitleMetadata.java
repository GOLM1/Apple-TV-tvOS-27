package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-demand title artwork and trending recommendations, with bounded requests and private metadata cache. */
final class TitleMetadata {
    interface Callback { void ready(JSONObject result); }
    interface TrendingCallback { void ready(List<ContinueWatching.Item> items); }

    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2);
    private static final ExecutorService LOCAL = Executors.newSingleThreadExecutor();
    private static final ExecutorService HOME = Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean HOME_REFRESH = new java.util.concurrent.atomic.AtomicBoolean();

    private static String key(ContinueWatching.Item item) {
        return item.packageName + ":" + item.title + ":" + item.subtitle + ":" + item.releaseDate + ":" + item.programType;
    }

    static void load(Context context, ContinueWatching.Item item, Callback callback) {
        Context ctx = context.getApplicationContext();
        LOCAL.execute(() -> {
            JSONObject cached = null;
            try {
                String stored = ctx.getSharedPreferences("title_metadata_v6", Context.MODE_PRIVATE).getString(key(item), null);
                if (stored != null) cached = new JSONObject(stored);
            } catch (Exception ignored) { }
            JSONObject local = cached != null && cached.optBoolean("found") ? cached : null;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.ready(local));
            if (cached != null && System.currentTimeMillis() - cached.optLong("saved") < cached.optLong("ttl")) return;
            WORKER.execute(() -> {
                try {
                    String token = ctx.getString(R.string.tmdb_read_token);
                    JSONObject fresh = token.isEmpty() ? null : resolve(ctx, token, item);
                    if (fresh != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.ready(fresh));
                } catch (Exception error) {
                    android.util.Log.w("TitleMetadata", "Metadata unavailable: " + error.getClass().getSimpleName());
                }
            });
        });
    }

    static void trending(Context context, TrendingCallback callback) {
        Context ctx = context.getApplicationContext();
        LOCAL.execute(() -> {
            List<ContinueWatching.Item> list = new ArrayList<>();
            SharedPreferences cache = ctx.getSharedPreferences("home_trending", Context.MODE_PRIVATE);
            String cached = cache.getString("items", null);
            long saved = cache.getLong("saved", 0);
            if (cached == null) {
                SharedPreferences legacy = ctx.getSharedPreferences("title_metadata_v6", Context.MODE_PRIVATE);
                cached = legacy.getString("__trending_cache__", null);
                saved = legacy.getLong("__trending_saved__", 0);
            }
            try {
                JSONArray rows = cached == null ? null : new JSONArray(cached);
                for (int i = 0; rows != null && i < Math.min(10, rows.length()); i++) list.add(deserializeItem(rows.getJSONObject(i)));
            } catch (Exception ignored) { }
            List<ContinueWatching.Item> local = list;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.ready(local));
            if (!list.isEmpty() && System.currentTimeMillis() - saved < 4 * 60 * 60 * 1000L) return;
            if (HOME_REFRESH.compareAndSet(false, true)) HOME.execute(() -> refreshTrending(ctx, cache, callback));
        });
    }

    private static void refreshTrending(Context ctx, SharedPreferences cache, TrendingCallback callback) {
            List<ContinueWatching.Item> list = new ArrayList<>();
            try {
                String token = ctx.getString(R.string.tmdb_read_token);
                if (!token.isEmpty()) {
                        JSONObject resp = get(token, "/trending/all/day?language=en-US");
                        if (resp != null) {
                            JSONArray results = resp.optJSONArray("results");
                            JSONArray toCache = new JSONArray();
                            for (int i = 0; results != null && i < results.length() && list.size() < 10; i++) {
                                JSONObject row = results.getJSONObject(i);
                                String type = row.optString("media_type");
                                if (!"movie".equals(type) && !"tv".equals(type)) continue;
                                int id = row.optInt("id");
                                String title = row.optString("movie".equals(type) ? "title" : "name");
                                String overview = row.optString("overview");
                                String backdrop = row.optString("backdrop_path");
                                if (backdrop == null || backdrop.isEmpty()) continue;
                                String art = "https://image.tmdb.org/t/p/w1280" + backdrop;
                                String date = row.optString("movie".equals(type) ? "release_date" : "first_air_date");
                                int progType = "movie".equals(type) ? 0 : 1;
                                String intent = "stremio:///detail/" + ("movie".equals(type) ? "movie" : "series") + "/tmdb:" + id;
                                ContinueWatching.Item item = new ContinueWatching.Item(
                                        title, overview, "com.stremio.one", art, intent,
                                        0, 0, false, null, null, date, progType);
                                list.add(item);
                                toCache.put(serializeItem(item));
                            }
                            if (!list.isEmpty()) {
                                cache.edit().putString("items", toCache.toString())
                                        .putLong("saved", System.currentTimeMillis()).apply();
                            }
                        }
                }
            } catch (Exception error) {
                android.util.Log.w("TitleMetadata", "Trending unavailable: " + error.getClass().getSimpleName());
            } finally { HOME_REFRESH.set(false); }
            List<ContinueWatching.Item> readyList = list;
            if (!list.isEmpty()) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.ready(readyList));
    }

    private static JSONObject serializeItem(ContinueWatching.Item item) throws Exception {
        return new JSONObject()
                .put("title", item.title)
                .put("subtitle", item.subtitle)
                .put("pkg", item.packageName)
                .put("art", item.artUri)
                .put("intent", item.intentUri)
                .put("date", item.releaseDate)
                .put("type", item.programType);
    }

    private static ContinueWatching.Item deserializeItem(JSONObject obj) {
        return new ContinueWatching.Item(
                obj.optString("title"), obj.optString("subtitle"), obj.optString("pkg"),
                obj.optString("art", null), obj.optString("intent", null),
                0, 0, false, null, null, obj.optString("date", null), obj.optInt("type", 0));
    }

    private static JSONObject resolve(Context ctx, String token, ContinueWatching.Item item) throws Exception {
        if (item.programType > 3) return null; // Clips/news are not films or series.
        // Verified against Shahid's English page season-962922-962925 and its title artwork.
        if ("net.mbc.shahidTV".equals(item.packageName)
                && "فريد".equals(item.title) && item.artUri != null && item.artUri.contains("Fareed")) {
            return new JSONObject().put("title", "The Golden Boy")
                    .put("overview", "Seeking to give his reckless grandson a lesson, rich patriarch Halis Aga decides to marry him to a girl from his hometown.")
                    .put("metadata", "TV Show · Drama · Romance");
        }
        SharedPreferences cache = ctx.getSharedPreferences("title_metadata_v6", Context.MODE_PRIVATE);
        String key = key(item);
        String stored = cache.getString(key, null);
        if (stored != null) {
            JSONObject old = new JSONObject(stored);
            if (System.currentTimeMillis() - old.optLong("saved") < old.optLong("ttl"))
                return old.optBoolean("found") ? old : null;
        }

        JSONObject match = null;
        if (item.intentUri != null && item.intentUri.contains("tmdb:")) {
            try {
                int idx = item.intentUri.indexOf("tmdb:");
                int tmdbId = Integer.parseInt(item.intentUri.substring(idx + 5).trim());
                String type = item.intentUri.contains("/series/") || item.programType == 1 ? "tv" : "movie";
                match = new JSONObject().put("id", tmdbId).put("media_type", type);
            } catch (Exception ignored) {}
        }

        if (match == null) {
            boolean arabic = TitleMatch.arabic(item.title);
            JSONArray rows = get(token, "/search/multi?include_adult=false&language="
                    + (arabic ? "ar-SA" : "en-US") + "&query="
                    + URLEncoder.encode(item.title, StandardCharsets.UTF_8.name())).getJSONArray("results");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String type = row.optString("media_type");
                if (!type.equals("tv") && !type.equals("movie")) continue;
                if (item.programType == 0 && !type.equals("movie")) continue;
                if (item.programType >= 1 && item.programType <= 3 && !type.equals("tv")) continue;
                // Episode release years are not the series premiere year.
                if (type.equals("movie") && item.releaseDate != null && item.releaseDate.length() >= 4
                        && !row.optString("release_date").startsWith(item.releaseDate.substring(0, 4))) continue;
                if (!TitleMatch.same(item.title, row.optString(type.equals("tv") ? "name" : "title"),
                        row.optString(type.equals("tv") ? "original_name" : "original_title"))) continue;
                // Localized names may refer to an unrelated work (e.g. Fareed/Franklin).
                if (arabic && !TitleMatch.corroborated(item.subtitle, row.optString("overview"))) continue;
                if (match != null) { match = null; break; } // Ambiguous title: retain the provider's text.
                match = row;
            }
        }

        JSONObject result = new JSONObject();
        result.put("saved", System.currentTimeMillis());
        result.put("ttl", match == null ? 6 * 60 * 60 * 1000L : 7 * 24 * 60 * 60 * 1000L);
        result.put("found", match != null);
        if (match != null) {
            String type = match.getString("media_type");
            JSONObject details = get(token, "/" + type + "/" + match.getInt("id")
                    + "?language=en-US&append_to_response=images,videos,external_ids,"
                    + (type.equals("tv") ? "content_ratings" : "release_dates") + "&include_image_language=en,null");
            result.put("rating", rating(details, type));
            JSONObject last = details.optJSONObject("last_episode_to_air");
            result.put("badge", badge(type, last == null ? "" : last.optString("air_date"),
                    last == null ? 0 : last.optInt("episode_number"), details.optString("release_date"),
                    java.time.LocalDate.now()));
            result.put("tmdb_id", details.optInt("id"));
            result.put("homepage", details.optString("homepage"));
            JSONObject ext = details.optJSONObject("external_ids");
            if (ext != null && ext.optString("imdb_id") != null && !ext.optString("imdb_id").isEmpty()) {
                result.put("imdb_id", ext.optString("imdb_id"));
            }
            JSONObject videos = details.optJSONObject("videos");
            JSONArray candidates = videos == null ? null : videos.optJSONArray("results");
            JSONObject trailer = null;
            if (candidates != null) for (int i = 0; i < candidates.length(); i++) {
                JSONObject candidate = candidates.getJSONObject(i);
                if (!"Trailer".equals(candidate.optString("type"))
                        || !"YouTube".equals(candidate.optString("site"))
                        || !candidate.optString("key").matches("[A-Za-z0-9_-]{11}")) continue;
                if (trailer == null || (!trailer.optBoolean("official") && candidate.optBoolean("official"))) trailer = candidate;
            }
            if (trailer != null) {
                JSONArray ordered = new JSONArray();
                ordered.put(trailer.optString("key"));
                for (int i = 0; candidates != null && i < candidates.length() && ordered.length() < 3; i++) {
                    JSONObject candidate = candidates.getJSONObject(i);
                    String videoId = candidate.optString("key");
                    if ("Trailer".equals(candidate.optString("type")) && "YouTube".equals(candidate.optString("site"))
                            && videoId.matches("[A-Za-z0-9_-]{11}") && !videoId.equals(trailer.optString("key"))) ordered.put(videoId);
                }
                result.put("youtube_trailers", ordered);
            }
            result.put("title", details.optString(type.equals("tv") ? "name" : "title"));
            result.put("overview", details.optString("overview"));
            StringBuilder meta = new StringBuilder(type.equals("tv") ? "TV Show" : "Movie");
            JSONArray genres = details.optJSONArray("genres");
            if (genres != null) for (int i = 0; i < Math.min(2, genres.length()); i++)
                meta.append(" · ").append(genres.getJSONObject(i).optString("name"));
            result.put("metadata", meta.toString());
            JSONObject images = details.optJSONObject("images");
            JSONArray logos = images == null ? null : images.optJSONArray("logos");
            JSONObject best = null;
            if (logos != null) for (int i = 0; i < logos.length(); i++) {
                JSONObject candidate = logos.getJSONObject(i);
                String path = candidate.optString("file_path");
                if (!path.endsWith(".png")) continue;
                if (best == null || score(candidate) > score(best)) best = candidate;
            }
            if (best != null) result.put("logo", "https://image.tmdb.org/t/p/w500" + best.getString("file_path"));
        }
        // Bound storage without clearing other launcher preferences.
        if (cache.getAll().size() > 120) {
            String oldest = null;
            long saved = Long.MAX_VALUE;
            for (java.util.Map.Entry<String, ?> entry : cache.getAll().entrySet()) {
                if (entry.getKey().startsWith("__") || !(entry.getValue() instanceof String)) continue;
                try {
                    long stamp = new JSONObject((String) entry.getValue()).optLong("saved");
                    if (stamp < saved) { saved = stamp; oldest = entry.getKey(); }
                } catch (Exception ignored) { }
            }
            if (oldest != null) cache.edit().remove(oldest).apply();
        }
        cache.edit().putString(key, result.toString()).apply();
        return match == null ? null : result;
    }

    /** The US age rating (TV-14, PG-13), as the Apple TV app shows it beside the genres; "" when TMDB has none. */
    private static String rating(JSONObject details, String type) {
        JSONObject block = details.optJSONObject(type.equals("tv") ? "content_ratings" : "release_dates");
        JSONArray rows = block == null ? null : block.optJSONArray("results");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !"US".equals(row.optString("iso_3166_1"))) continue;
            if (type.equals("tv")) return row.optString("rating");
            JSONArray dates = row.optJSONArray("release_dates");
            for (int j = 0; dates != null && j < dates.length(); j++) {
                String certification = dates.optJSONObject(j) == null ? "" : dates.optJSONObject(j).optString("certification");
                if (!certification.isEmpty()) return certification;
            }
        }
        return "";
    }

    /**
     * The label over the title, as in the Apple TV app: a show whose latest episode aired in the last 30 days is a
     * "New Season" when that episode opened a season, else a "New Episode"; a film out in the last 60 days is "New".
     * Dates are TMDB's yyyy-MM-dd; anything else (or older) gives "".
     */
    static String badge(String type, String lastAirDate, int episodeNumber, String releaseDate, java.time.LocalDate today) {
        try {
            if ("tv".equals(type) && lastAirDate != null && !lastAirDate.isEmpty()) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(lastAirDate), today);
                if (days >= 0 && days <= 30) return episodeNumber == 1 ? "New Season" : "New Episode";
            } else if ("movie".equals(type) && releaseDate != null && !releaseDate.isEmpty()) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(releaseDate), today);
                if (days >= 0 && days <= 60) return "New";
            }
        } catch (java.time.format.DateTimeParseException ignored) {
            // A malformed date gives no label rather than a wrong one.
        }
        return "";
    }

    private static double score(JSONObject logo) {
        int w = logo.optInt("width", 1);
        int h = logo.optInt("height", 1);
        double ratio = (double) w / Math.max(1, h);
        double penalty = (ratio > 5.5 || ratio < 1.2) ? -50 : 0;
        return ("en".equals(logo.optString("iso_639_1")) ? 100 : 0) + logo.optDouble("vote_average") + penalty;
    }

    private static JSONObject get(String token, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.themoviedb.org/3" + path).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("Metadata unavailable");
            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 1024 * 1024) throw new IOException("Metadata too large");
                    out.write(buffer, 0, n);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { c.disconnect(); }
    }
}
