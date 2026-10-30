package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recommendations from the household API at watch.kokohome.de: who's present, what to watch on which
 * service. Three tabs (Explore from `results`, Movies from `movies`, Shows from `series`), each with
 * up to 5 titles. Toggling a user re-fetches with only the active ones; the screen auto-refreshes
 * every 20 minutes.
 */
final class KokoRecs {
    static final class User {
        String id, name, avatarUrl;
        boolean active = true;
        int color;
    }

    static final class Title {
        int tmdbId;
        String mediaType;
        String title;
        int year;
        String posterUrl, backdropUrl;
        int matchPercent;
        String reason;
        String[] genres, providers;
        Bitmap poster;
        BitmapShader posterShader;
        String pkg, link;
        Bitmap backdropBitmap;
    }

    interface Listener { void onKokoRecs(); }

    private static final long REFRESH_MS = 20 * 60_000L;
    private static final long RETRY_MS = 2 * 60_000L;
    private static final int POSTER_W = ui(220), POSTER_H = ui(330);

    private static int ui(int px) {
        android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
        int longest = Math.max(m.widthPixels, m.heightPixels);
        float s = longest > 0 ? Math.min(1f, longest / 1920f) : 1f;
        return Math.max(1, Math.round(px * s));
    }

    private static KokoRecs instance;

    static synchronized KokoRecs get(Context c) {
        if (instance == null) instance = new KokoRecs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();

    private volatile List<User> users = new ArrayList<>();
    private volatile List<Title> results = new ArrayList<>();
    private volatile List<Title> movies = new ArrayList<>();
    private volatile List<Title> series = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long fetchedAt;
    private volatile long failedAt;
    private volatile String error = "";

    private KokoRecs(Context c) {
        context = c;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    List<User> users() { return users; }

    List<Title> forTab(int tab) {
        switch (tab) {
            case 1: return movies;
            case 2: return series;
            default: return results;
        }
    }

    static boolean configured() {
        String url = Config.KOKOHOME_URL;
        return url != null && !url.trim().isEmpty();
    }

    private static String base() {
        String url = Config.KOKOHOME_URL.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    void refreshIfStale() {
        if (!configured() || fetching) return;
        long now = System.currentTimeMillis();
        if (fetchedAt != 0 && now - fetchedAt < REFRESH_MS && now >= fetchedAt) return;
        if (failedAt != 0 && now - failedAt < RETRY_MS && now >= failedAt) return;
        fetch();
    }

    void fetch() {
        if (!configured() || fetching) return;
        fetching = true;
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("limit", 5);
                List<String> active = activeUserNames();
                if (active != null) body.put("users", new JSONArray(active));

                String response = post(base() + "/api/v1/recommendations", body.toString());
                JSONObject json = new JSONObject(response);
                parseResponse(json);
                fetchedAt = System.currentTimeMillis();
                failedAt = 0;
                error = "";
                resolveProviders();
                downloadPosters();
                downloadBackdrops();
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("kokohome: " + error);
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    void toggleUser(String userId) {
        for (User u : users) {
            if (u.id.equals(userId)) {
                u.active = !u.active;
                break;
            }
        }
        fetchedAt = 0;
        fetch();
    }

    /** All users active: no filter needed (the API returns household recommendations). */
    private List<String> activeUserNames() {
        if (users.isEmpty()) return null;
        boolean anyOff = false;
        for (User u : users) if (!u.active) anyOff = true;
        if (!anyOff) return null;
        List<String> names = new ArrayList<>();
        for (User u : users) if (u.active) names.add(u.name);
        return names.isEmpty() ? null : names;
    }

    private void parseResponse(JSONObject json) throws Exception {
        JSONArray present = json.optJSONArray("present_users");
        if (present != null) {
            Map<String, Boolean> prev = new HashMap<>();
            for (User u : users) prev.put(u.id, u.active);
            List<User> next = new ArrayList<>();
            for (int i = 0; i < present.length(); i++) {
                JSONObject u = present.getJSONObject(i);
                User user = new User();
                user.id = u.optString("id");
                user.name = u.optString("name");
                String av = u.optString("avatar_url", "");
                user.avatarUrl = av.startsWith("/") ? base() + av : av;
                Boolean was = prev.get(user.id);
                user.active = was != null ? was : true;
                user.color = userColor(user.name);
                next.add(user);
            }
            users = next;
        }
        // New API structure: mixed.results, movies.results, tv.results
        JSONObject mixed = json.optJSONObject("mixed");
        results = mixed != null ? parseTitles(mixed.optJSONArray("results")) : new ArrayList<>();

        JSONObject moviesObj = json.optJSONObject("movies");
        movies = moviesObj != null ? parseTitles(moviesObj.optJSONArray("results")) : new ArrayList<>();

        JSONObject tvObj = json.optJSONObject("tv");
        series = tvObj != null ? parseTitles(tvObj.optJSONArray("results")) : new ArrayList<>();
    }

    private static List<Title> parseTitles(JSONArray arr) {
        List<Title> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Title t = new Title();
            t.tmdbId = o.optInt("tmdb_id");
            t.mediaType = o.optString("media_type", "movie");
            t.title = o.optString("title", "");
            t.year = o.optInt("year");
            t.posterUrl = o.optString("poster_url", "");
            t.backdropUrl = o.optString("backdrop_url", "");
            t.matchPercent = o.optInt("match_percent", 0);
            t.reason = o.optString("reason", "");
            t.genres = strArray(o.optJSONArray("genres"));
            t.providers = strArray(o.optJSONArray("providers"));
            list.add(t);
        }
        return list;
    }

    private static String[] strArray(JSONArray a) {
        if (a == null) return new String[0];
        String[] out = new String[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.optString(i, "");
        return out;
    }

    /** Maps provider names ("Netflix") to the TV's installed app, using Recs' service table. */
    private void resolveProviders() {
        for (List<Title> list : new List[] {results, movies, series}) {
            for (Title t : list) {
                if (t.providers.length == 0 || t.pkg != null) continue;
                for (String prov : t.providers) {
                    String key = providerKey(prov);
                    if (key == null) continue;
                    String pkg = installedPkg(key);
                    if (pkg != null) {
                        t.pkg = pkg;
                        break;
                    }
                }
            }
        }
    }

    private static String providerKey(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("netflix")) return "netflix";
        if (n.contains("prime")) return "prime";
        if (n.contains("disney")) return "disney";
        if (n.contains("max")) return "max";
        if (n.contains("apple")) return "appletv";
        if (n.contains("paramount")) return "paramount";
        if (n.contains("hulu")) return "hulu";
        if (n.contains("peacock")) return "peacock";
        if (n.contains("globoplay")) return "globoplay";
        if (n.contains("crunchyroll")) return "crunchyroll";
        return null;
    }

    private String installedPkg(String key) {
        String[][] services = {
            {"netflix", "com.netflix.ninja"},
            {"prime", "com.amazon.amazonvideo.livingroom"}, {"prime", "com.amazon.firebat"}, {"prime", "com.amazon.avod"},
            {"disney", "com.disney.disneyplus"},
            {"max", "com.wbd.stream"}, {"max", "com.hbo.hbonow"},
            {"appletv", "com.apple.atve.androidtv.appletv"}, {"appletv", "com.apple.atve.sony.appletv"},
            {"paramount", "com.cbs.ott"}, {"paramount", "com.cbs.ca"},
            {"hulu", "com.hulu.livingroomplus"}, {"hulu", "com.hulu.plus"},
            {"peacock", "com.peacocktv.peacockandroid"},
            {"globoplay", "com.globo.globotv"},
            {"crunchyroll", "com.crunchyroll.crunchyroid"},
        };
        for (String[] s : services) {
            if (s[0].equals(key) && HomeApps.launchIntent(context.getPackageManager(), s[1]) != null) return s[1];
        }
        return null;
    }

    private void downloadPosters() {
        File dir = new File(context.getCacheDir(), "kokohome");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        boolean any = false;
        for (List<Title> list : new List[] {results, movies, series}) {
            for (Title t : list) {
                if (t.posterUrl.isEmpty() || t.poster != null) continue;
                try {
                    File f = new File(dir, safe(t.posterUrl));
                    if (!f.exists()) download(t.posterUrl, f);
                    BitmapFactory.Options probe = new BitmapFactory.Options();
                    probe.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getPath(), probe);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 1;
                    while (probe.outWidth / (opts.inSampleSize * 2) >= POSTER_W) opts.inSampleSize *= 2;
                    Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), opts);
                    if (bmp == null) continue;
                    if (bmp.getWidth() != POSTER_W || bmp.getHeight() != POSTER_H) {
                        Bitmap scaled = Bitmap.createScaledBitmap(bmp, POSTER_W, POSTER_H, true);
                        if (scaled != bmp) bmp.recycle();
                        bmp = scaled;
                    }
                    t.poster = bmp;
                    t.posterShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    any = true;
                } catch (Exception e) {
                    AppLog.w("kokohome poster: " + e.getMessage());
                }
            }
        }
        if (any) notifyListeners();
    }

    private void downloadBackdrops() {
        File dir = new File(context.getCacheDir(), "kokohome");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        boolean any = false;
        for (List<Title> list : new List[] {results, movies, series}) {
            for (Title t : list) {
                if (t.backdropUrl.isEmpty() || t.backdropBitmap != null) continue;
                try {
                    File f = new File(dir, "bd_" + safe(t.backdropUrl));
                    if (!f.exists()) download(t.backdropUrl, f);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), opts);
                    if (bmp == null) continue;
                    t.backdropBitmap = bmp;
                    any = true;
                } catch (Exception e) {
                    AppLog.w("kokohome backdrop: " + e.getMessage());
                }
            }
        }
        if (any) notifyListeners();
    }

    private static String safe(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url.replaceAll("[^a-zA-Z0-9.]", "_");
    }

    private static void download(String url, File dest) throws IOException {
        File part = new File(dest.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(dest)) throw new IOException("couldn't save poster");
    }

    private static String post(String url, String jsonBody) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                return buf.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onKokoRecs(); });
    }

    private static int userColor(String name) {
        int[] palette = {
            0xFF5E5CE6, 0xFFFF375F, 0xFFFF9F0A, 0xFF30D158,
            0xFF64D2FF, 0xFFBF5AF2, 0xFFFF6482, 0xFF40C8E0
        };
        int h = 0;
        for (int i = 0; i < name.length(); i++) h = h * 31 + name.charAt(i);
        return palette[Math.abs(h) % palette.length];
    }
}
