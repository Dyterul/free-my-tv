package io.freemytv;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home Assistant over its REST API: the states the home screen's right column shows, read every few seconds while the
 * home screen is on, and the services its buttons call. The address and the long-lived access token come from
 * config.properties (build.sh writes them into Config) or, when they were typed on the TV, from Prefs.
 * Only https works: the app refuses cleartext traffic (AndroidManifest).
 * Everything here runs off the UI thread; Listener is called back on it.
 */
final class HomeAssistant {
    /** Every call lands on the main thread: `onState` redraws the panel, `onError` shows a toast. */
    interface Listener {
        void onState();
        void onError(String why);
    }

    static final String AREA = "wohnzimmer";
    /** On while some light in the living room is on: the toggle's glow. */
    static final String AREA_LIGHT = "binary_sensor.wohnzimmer_licht_status";
    /** All the living room's lights in one light. */
    static final String MASTER = "light.lichter_an_aus";
    static final String DREAMVIEW = "input_boolean.dreamview";
    static final String SCENE_COZY = "scene.cozy_orange";
    static final String SCENE_DAYLIGHT = "scene.wohnzimmer_tageslicht";
    /** The slider's steps, in percent. */
    static final int STEP = 10;

    private static final long POLL_MS = 5000, OFFLINE_MS = 20_000, AFTER_CALL_MS = 800;
    private static final int TIMEOUT_MS = 6000;

    /** Made by start() and shut down by stop(), so a home screen that is gone leaves no thread behind. */
    private ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean running;
    /** Bumped by stop(): a request still in flight then answers nothing (a paused home screen, or a new instance). */
    private int generation;

    // What the panel draws (written from the network thread, read from the UI thread).
    volatile boolean areaOn, masterOn, dreamviewOn, online, asked;
    /** Whether an address and a token are set, read once per poll: the panel checks it on every frame it draws. */
    private volatile boolean ready;
    /** 0 to 100; -1 while neither Home Assistant nor the person has said which. */
    volatile int brightness = -1;

    private final Runnable poll = this::poll;

    static boolean configured() {
        return !Prefs.haUrl().isEmpty() && !Prefs.haToken().isEmpty();
    }

    /** configured(), as of the last poll (no reading of the settings while drawing). */
    boolean ready() {
        return ready;
    }

    void start(Listener l) {
        listener = l;
        running = true;
        generation++;
        if (io == null) io = Executors.newSingleThreadExecutor();
        ready = configured();
        poll();
    }

    void stop() {
        running = false;
        listener = null;
        generation++;
        main.removeCallbacks(poll);
        if (io != null) {
            io.shutdown();   // what is in flight finishes; its answer is dropped (running is false)
            io = null;
        }
    }

    /** Reads the states again in a moment: right after a button, so its new state shows without waiting for the poll. */
    void refresh() {
        schedule(AFTER_CALL_MS);
    }

    private void schedule(long delay) {
        if (!running) return;
        main.removeCallbacks(poll);
        main.postDelayed(poll, delay);
    }

    private void poll() {
        if (!running) return;
        ready = configured();   // the settings may have been changed in the other process
        if (!ready) {
            online = false;
            asked = false;
            schedule(POLL_MS);
            return;
        }
        final String base = Prefs.haUrl(), token = Prefs.haToken();
        final int gen = generation;
        final ExecutorService pool = io;
        if (pool == null) return;
        pool.execute(() -> {
            boolean reached = false;
            try {
                JSONObject area = get(base, token, "/api/states/" + AREA_LIGHT);
                JSONObject master = get(base, token, "/api/states/" + MASTER);
                JSONObject dream = get(base, token, "/api/states/" + DREAMVIEW);
                areaOn = "on".equals(area.optString("state"));
                masterOn = "on".equals(master.optString("state"));
                dreamviewOn = "on".equals(dream.optString("state"));
                int pct = brightnessOf(master);
                if (pct >= 0) brightness = pct;   // a light group has one; an empty area may not
                reached = true;
            } catch (Exception e) {
                AppLog.w("Home Assistant: " + e.getMessage());
            }
            final boolean ok = reached;
            main.post(() -> {
                if (!running || gen != generation) return;
                asked = true;
                online = ok;
                if (listener != null) listener.onState();
                schedule(ok ? POLL_MS : OFFLINE_MS);   // unreachable: ask less often
            });
        });
    }

    // MARK: - What the buttons do

    void toggleArea() {
        areaOn = !areaOn;     // shows at once; the next poll corrects it where Home Assistant disagrees
        stateChanged();
        call("/api/services/light/toggle", "{\"area_id\":\"" + AREA + "\"}");
    }

    void toggleMaster() {
        masterOn = !masterOn;
        stateChanged();
        call("/api/services/light/toggle", "{\"entity_id\":\"" + MASTER + "\"}");
    }

    void toggleDreamview() {
        dreamviewOn = !dreamviewOn;
        stateChanged();
        call("/api/services/input_boolean/toggle", "{\"entity_id\":\"" + DREAMVIEW + "\"}");
    }

    void scene(String entityId) {
        call("/api/services/scene/turn_on", "{\"entity_id\":\"" + entityId + "\"}");
    }

    /** The slider: ← and → move it by STEP, in 10% steps from 0 to 100. */
    void adjustBrightness(int delta) {
        int from = brightness < 0 ? 50 : brightness;   // first touch of a light that has no brightness: the middle
        setBrightness(from + delta);
    }

    void setBrightness(int pct) {
        int next = Math.max(0, Math.min(100, Math.round(pct / (float) STEP) * STEP));
        brightness = next;
        stateChanged();
        call("/api/services/light/turn_on", "{\"area_id\":\"" + AREA + "\",\"brightness_pct\":" + next + "}");
    }

    private void stateChanged() {
        if (listener != null) listener.onState();
    }

    /** A service call, in the background; its answer only says whether to read the states again. */
    private void call(String path, String body) {
        if (!configured()) {
            if (listener != null) listener.onError(Ui.tr("Set up Home Assistant in Settings."));
            return;
        }
        final String base = Prefs.haUrl(), token = Prefs.haToken();
        final ExecutorService pool = io;
        if (pool == null) return;
        pool.execute(() -> {
            try {
                request(base, token, path, body);
                main.post(() -> {
                    if (!running) return;
                    online = true;
                    stateChanged();
                    refresh();
                });
            } catch (Exception e) {
                AppLog.w("Home Assistant: " + e.getMessage());
                main.post(() -> {
                    if (!running) return;
                    online = false;
                    stateChanged();
                    if (listener != null) listener.onError(Ui.tr("Home Assistant didn't answer."));
                });
            }
        });
    }

    // MARK: - REST

    private static JSONObject get(String base, String token, String path) throws Exception {
        return new JSONObject(request(base, token, path, null));
    }

    /** The answer's text; anything but a 2xx throws, with Home Assistant's own message where it sends one. */
    private static String request(String base, String token, String path, String body) throws IOException {
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        HttpURLConnection c = (HttpURLConnection) new URL(root + path).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
            }
            int code = c.getResponseCode();
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) {
                String why = text.trim();
                if (why.length() > 120) why = why.substring(0, 120);
                throw new IOException("HTTP " + code + (why.isEmpty() ? "" : ": " + why));
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = s.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** A light's brightness in percent, or -1 where Home Assistant has none for it (off, or not a dimmable light). */
    private static int brightnessOf(JSONObject state) {
        JSONObject attrs = state.optJSONObject("attributes");
        if (attrs == null) return -1;
        int raw = attrs.optInt("brightness", -1);
        if (raw < 0) return -1;
        return Math.max(0, Math.min(100, Math.round(raw * 100f / 255f)));
    }
}
