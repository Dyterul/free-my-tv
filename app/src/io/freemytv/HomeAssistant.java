package io.freemytv;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.JsonReader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Home Assistant over its own WebSocket API (the one its frontend speaks), instead of polling REST:
 *  - one connection to wss://<address>/api/websocket, authenticated with the long-lived token from config.properties
 *    (or, typed on the TV, from Prefs), then `get_states` once and `subscribe_events` for `state_changed`, so every
 *    switch in the panel is live (typ. well under a second) instead of up to five seconds old;
 *  - the buttons call their services over the same socket (`call_service`), and a click shows its new state at once
 *    while the subscription corrects it;
 *  - the socket is kept alive with Home Assistant's own ping (answered by a pong) and dropped — and retried, backing
 *    off — when nothing at all arrives for a while, so the panel's status can say Online or Offline truthfully.
 * Only wss works: the app refuses cleartext traffic (AndroidManifest).
 * Everything here runs off the UI thread; Listener is called back on it. No libraries: this is the RFC 6455 client the
 * app needs, nothing more (text frames, ping/pong/close, fragmentation).
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
    /** The living room's lights as one light: the slider reads its brightness. */
    static final String MASTER = "light.lichter_an_aus";
    /** The TV light mode, set by the panel's segmented control (input_select.select_option). */
    static final String TV_LIGHT = "input_select.tv_light_modus";
    /** Its three options, in the order the segmented control shows them. */
    static final String[] MODES = {"Aus", "TV-AmbientLight", "DreamView"};
    static final String SCENE_COZY = "scene.cozy_orange";
    static final String SCENE_DAYLIGHT = "scene.wohnzimmer_tageslicht";
    static final String SCENE_READING = "scene.leselicht";
    /** The slider's steps, in percent. */
    static final int STEP = 10;

    private static final String PATH = "/api/websocket";
    private static final int CONNECT_MS = 10_000, READ_MS = 5_000;
    private static final int PING_MS = 25_000;     // {"type":"ping"} when nothing else goes out
    private static final int IDLE_MS = 70_000;     // nothing at all for this long: the connection is dead
    private static final int RETRY_MS = 5_000, RETRY_MAX_MS = 30_000;
    private static final int MAX_MESSAGE = 16 << 20;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger ids = new AtomicInteger();
    /** Text frames waiting for the writer thread (a button must never wait for the socket). */
    private final LinkedBlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
    private final Object lock = new Object();
    private volatile Socket socket;
    private OutputStream out;
    private InputStream in;

    private Listener listener;
    private boolean running;
    /** Bumped by stop(): a connection still in flight then answers nothing (a paused home screen). */
    private int generation;
    private Thread thread;

    // What the panel draws (written from the socket thread, read from the UI thread).
    volatile boolean areaOn, online, asked;
    /** input_select.tv_light_modus as Home Assistant last reported it ("" until it says). */
    volatile String mode = "";
    /** 0 to 100; -1 while neither Home Assistant nor the person has said which. */
    volatile int brightness = -1;
    /** Whether an address and a token are set (no reading of the settings while drawing). */
    private volatile boolean ready;

    static boolean configured() {
        return !Prefs.haUrl().isEmpty() && !Prefs.haToken().isEmpty();
    }

    void start(Listener l) {
        if (running) return;
        listener = l;
        running = true;
        ready = configured();
        final int gen = ++generation;
        Thread t = new Thread(() -> run(gen), "ha-ws");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    void stop() {
        running = false;
        listener = null;
        generation++;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        close();
        outbox.clear();
    }

    // MARK: - What the buttons do

    void toggleArea() {
        areaOn = !areaOn;     // shows at once; the subscription corrects it where Home Assistant disagrees
        stateChanged();
        call("light", "toggle", "{\"area_id\":\"" + AREA + "\"}");
    }

    /** A segment of the TV light mode: input_select.select_option with that option. */
    void selectMode(String option) {
        mode = option;
        stateChanged();
        call("input_select", "select_option",
                "{\"entity_id\":\"" + TV_LIGHT + "\",\"option\":\"" + option + "\"}");
    }

    void scene(String entityId) {
        call("scene", "turn_on", "{\"entity_id\":\"" + entityId + "\"}");
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
        call("light", "turn_on", "{\"area_id\":\"" + AREA + "\",\"brightness_pct\":" + next + "}");
    }

    private void stateChanged() {
        final Listener l = listener;
        if (l == null || !running) return;
        main.post(() -> {
            Listener cur = listener;
            if (running && cur != null) cur.onState();
        });
    }

    private void failed(String why) {
        final Listener l = listener;
        if (l == null || !running) return;
        main.post(() -> {
            Listener cur = listener;
            if (running && cur != null) cur.onError(why);
        });
    }

    /** A service call, over the open socket. Without one, the panel says so instead of waiting. */
    private void call(String domain, String service, String data) {
        if (!configured()) {
            failed(Ui.tr("Set up Home Assistant in Settings."));
            return;
        }
        if (!online) {
            failed(Ui.tr("Home Assistant didn't answer."));
            return;
        }
        send("{\"id\":" + ids.incrementAndGet() + ",\"type\":\"call_service\",\"domain\":\"" + domain
                + "\",\"service\":\"" + service + "\",\"service_data\":" + data + "}");
    }

    /** Queues a text frame for the writer thread; the caller never touches the socket. */
    private void send(String json) {
        if (socket == null) return;
        outbox.offer(frame(0x1, json.getBytes(StandardCharsets.UTF_8), true));
    }

    // MARK: - The connection: connect, authenticate, subscribe, read, retry

    private void run(int gen) {
        long retry = RETRY_MS;
        while (running && gen == generation) {
            boolean connected = false;
            try {
                connect(gen);
                connected = true;
            } catch (IOException | RuntimeException e) {
                AppLog.w("Home Assistant: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                close();
                outbox.clear();
            }
            if (!running || gen != generation) return;
            if (online) {
                online = false;
                stateChanged();   // the panel's dot turns grey at once
            }
            asked = true;
            stateChanged();
            if (connected) retry = RETRY_MS;   // it worked: an interruption gets a quick retry
            if (!sleep(retry)) return;
            retry = Math.min(RETRY_MAX_MS, retry * 2);   // unreachable: ask less and less often
        }
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void connect(int gen) throws IOException {
        ready = configured();
        if (!ready) throw new IOException("no address or token");
        String url = Prefs.haUrl();
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Home Assistant's address is not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        boolean tls = scheme.equals("wss") || scheme.equals("https");
        if (!tls) {
            throw new IOException("only https:// works for Home Assistant (websocket over TLS)");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IOException("Home Assistant's address is not a URL");
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.endsWith("/api/websocket")) path = path.substring(0, path.length() - "/api/websocket".length());
        path += PATH;

        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_MS);
            s.setTcpNoDelay(true);
            if (tls) {
                SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) f.createSocket(s, host, port, true);
                SSLParameters p = ssl.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");   // the certificate has to match the name
                ssl.setSSLParameters(p);
                ssl.startHandshake();
                s = ssl;
            }
            s.setSoTimeout(READ_MS);
            synchronized (lock) {
                socket = s;
                out = new BufferedOutputStream(s.getOutputStream());
                in = new BufferedInputStream(s.getInputStream());
            }
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        handshake(host, port, path);
        authenticate();
        // Authenticated: from here on the socket is live.
        online = true;
        asked = true;
        stateChanged();
        Thread writer = new Thread(() -> write(gen), "ha-ws-write");
        writer.setDaemon(true);
        writer.start();
        // Subscribe first, then take the states: a change that happens in between still lands (the snapshot is the
        // newer one in that case), so nothing is lost to the ordering.
        send("{\"id\":" + ids.incrementAndGet()
                + ",\"type\":\"subscribe_events\",\"event_type\":\"state_changed\"}");
        send("{\"id\":" + ids.incrementAndGet() + ",\"type\":\"get_states\"}");
        read(gen);
    }

    /** The HTTP upgrade: 101 Switching Protocols, then Home Assistant says auth_required. */
    private void handshake(String host, int port, String path) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String hostHeader = port == 443 ? host : host + ":" + port;
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.encodeToString(nonce, Base64.NO_WRAP) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String status = readLine();
        if (!status.contains("101")) {
            String why = status.trim();
            throw new IOException(why.isEmpty() ? "no answer" : why);
        }
        for (String line = readLine(); !line.isEmpty(); line = readLine()) {
            if (line.length() > 8192) throw new IOException("the answer's headers are too long");
        }
    }

    private void authenticate() throws IOException {
        Message m = new Message();
        next(m);   // auth_required
        if (!"auth_required".equals(m.type) && !"auth_ok".equals(m.type)) {
            throw new IOException("not a Home Assistant websocket (" + m.type + ")");
        }
        if ("auth_required".equals(m.type)) {
            fire("{\"type\":\"auth\",\"access_token\":\"" + json(Prefs.haToken()) + "\"}");
            m = new Message();
            next(m);
        }
        if ("auth_invalid".equals(m.type)) throw new IOException("the token was refused");
        if (!"auth_ok".equals(m.type)) throw new IOException("authentication failed (" + m.type + ")");
    }

    /** Reads, answers control frames and dispatches, until the connection ends. */
    private void read(int gen) throws IOException {
        long seen = System.currentTimeMillis();
        while (running && gen == generation) {
            byte[] data;
            try {
                data = readMessage();
            } catch (java.net.SocketTimeoutException e) {
                if (System.currentTimeMillis() - seen >= IDLE_MS) throw new IOException("stopped answering");
                continue;   // the writer's pings keep it alive
            }
            seen = System.currentTimeMillis();
            if (data == null) continue;   // a control frame
            Message m = new Message();
            try {
                parse(data, m);
            } catch (Exception e) {
                AppLog.w("Home Assistant: bad message (" + e.getMessage() + ")");
                continue;
            }
            if ("event".equals(m.type)) {
                if (m.entity != null) apply(m.entity, m.state, m.hasBrightness, m.brightness);
                stateChanged();
            } else if ("result".equals(m.type) && !m.success) {
                AppLog.w("Home Assistant: " + m.error);
            }
        }
    }

    private void apply(String entity, String state, boolean hasBrightness, int brightness) {
        if (AREA_LIGHT.equals(entity)) {
            areaOn = "on".equals(state);
        } else if (MASTER.equals(entity)) {
            if (hasBrightness) this.brightness = pct(brightness);   // where the slider stands
        } else if (TV_LIGHT.equals(entity)) {
            mode = state == null ? "" : state;
        }
    }

    private static int pct(int raw) {
        return Math.max(0, Math.min(100, Math.round(raw * 100f / 255f)));
    }

    // MARK: - Frames (RFC 6455)

    private byte[] frame(int opcode, byte[] payload, boolean mask) {
        int len = payload.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream(len + 14);
        b.write(0x80 | opcode);
        int flag = mask ? 0x80 : 0;
        if (len <= 125) {
            b.write(flag | len);
        } else if (len <= 0xFFFF) {
            b.write(flag | 126);
            b.write((len >>> 8) & 255);
            b.write(len & 255);
        } else {
            b.write(flag | 127);
            for (int i = 7; i >= 0; i--) b.write((int) (((long) len >>> (8 * i)) & 255));
        }
        if (mask) {
            byte[] key = new byte[4];
            random.nextBytes(key);
            b.write(key, 0, 4);
            for (int i = 0; i < len; i++) b.write(payload[i] ^ key[i & 3]);
        } else {
            b.write(payload, 0, len);
        }
        return b.toByteArray();
    }

    /**
     * The writer thread: the queued frames and, when nothing goes out, Home Assistant's own ping (which it answers
     * with a pong, so a quiet house still proves the connection is alive — and the reader sees the answer).
     */
    private void write(int gen) {
        try {
            while (running && gen == generation) {
                byte[] queued = outbox.poll(PING_MS, TimeUnit.MILLISECONDS);
                if (queued == null) {
                    queued = frame(0x1, ("{\"id\":" + ids.incrementAndGet() + ",\"type\":\"ping\"}")
                            .getBytes(StandardCharsets.UTF_8), true);
                }
                synchronized (lock) {
                    if (socket == null) return;
                    out.write(queued);
                    out.flush();
                }
            }
        } catch (Exception e) {   // the reader notices the closed socket on its next timeout
            close();
        }
    }

    /** One message: fragmented text/binary frames joined, control frames answered (null). */
    private byte[] readMessage() throws IOException {
        ByteArrayOutputStream joined = null;
        while (true) {
            int b0 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            int b1 = readByte();
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            if (len > MAX_MESSAGE) throw new IOException("message too large (" + len + ")");
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            switch (opcode) {
                case 0x9:   // ping: answer it (the writer thread helps pin the socket that wrote what is queued)
                    outbox.offer(frame(0xA, payload, true));
                    return null;
                case 0xA:   // pong
                    return null;
                case 0x8:
                    throw new IOException("closed by Home Assistant");
                case 0x1:
                case 0x2:
                case 0x0:
                    break;
                default:
                    return null;   // unknown: ignore
            }
            if (joined != null) {
                joined.write(payload, 0, payload.length);
                if (fin) return joined.toByteArray();
            } else if (fin) {
                return payload;
            } else {
                joined = new ByteArrayOutputStream();
                joined.write(payload, 0, payload.length);
            }
        }
    }

    private void fire(String json) throws IOException {
        synchronized (lock) {
            if (socket == null) throw new IOException("no connection");
            out.write(frame(0x1, json.getBytes(StandardCharsets.UTF_8), true));
            out.flush();
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("closed by Home Assistant");
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int at = 0;
        while (at < buf.length) {
            int n = in.read(buf, at, buf.length - at);
            if (n < 0) throw new IOException("closed by Home Assistant");
            at += n;
        }
    }

    private String readLine() throws IOException {
        StringBuilder s = new StringBuilder();
        while (true) {
            int b = readByte();
            if (b == '\n') return s.toString().trim();
            if (b != '\r') s.append((char) b);
            if (s.length() > 16_384) throw new IOException("the answer's headers are too long");
        }
    }

    private void close() {
        synchronized (lock) {
            Socket s = socket;
            socket = null;
            in = null;
            out = null;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String json(String text) {
        StringBuilder s = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') s.append('\\').append(c);
            else if (c < 0x20) s.append(String.format("\\u%04x", (int) c));
            else s.append(c);
        }
        return s.toString();
    }

    // MARK: - Messages

    /** The handful of fields the panel needs, read without building the whole (sometimes large) answer. */
    private static final class Message {
        String type = "", error = "";
        boolean success = true;
        String event = "";
        String entity;
        String state;
        boolean hasBrightness;
        int brightness;
    }

    private void next(Message out) throws IOException {
        byte[] data = readMessage();
        if (data == null) throw new IOException("no answer");
        parse(data, out);
    }

    /**
     * Walks one Home Assistant message. Streamed with JsonReader: `get_states` sends every entity with every
     * attribute at once (megabytes on a large home), and none of it is kept except the three entities the panel shows.
     */
    private void parse(byte[] data, Message m) throws IOException {
        JsonReader r = new JsonReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        try {
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if ("type".equals(name)) {
                    m.type = string(r);
                } else if ("success".equals(name)) {
                    if (r.peek() == android.util.JsonToken.BOOLEAN) m.success = r.nextBoolean();
                    else r.skipValue();
                } else if ("error".equals(name)) {
                    m.error = error(r);
                } else if ("event".equals(name)) {
                    event(r, m);
                } else if ("result".equals(name)) {
                    if (r.peek() == android.util.JsonToken.BEGIN_ARRAY) states(r);   // the get_states answer
                    else r.skipValue();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        } finally {
            r.close();
        }
    }

    private static String error(JsonReader r) throws IOException {
        if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return "";
        }
        String message = "";
        r.beginObject();
        while (r.hasNext()) {
            if ("message".equals(r.nextName())) message = string(r);
            else r.skipValue();
        }
        r.endObject();
        return message;
    }

    /** event.event_type and, for a state_changed event, which entity changed and to what. */
    private void event(JsonReader r, Message m) throws IOException {
        if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return;
        }
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if ("event_type".equals(name)) {
                m.event = string(r);
            } else if ("data".equals(name) && r.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    String field = r.nextName();
                    if ("entity_id".equals(field)) {
                        m.entity = string(r);
                    } else if ("new_state".equals(field) && r.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                        state(r, m);
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        if (!"state_changed".equals(m.event)) m.entity = null;
    }

    /** The get_states answer: one state per entity, and only the panel's three are kept. */
    private void states(JsonReader r) throws IOException {
        r.beginArray();
        while (r.hasNext()) {
            Message m = new Message();
            if (r.peek() == android.util.JsonToken.BEGIN_OBJECT) state(r, m);
            else r.skipValue();
            if (m.entity != null) apply(m.entity, m.state, m.hasBrightness, m.brightness);
        }
        r.endArray();
    }

    /** One state object: its state, and (for a light) its brightness. */
    private void state(JsonReader r, Message m) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if ("state".equals(name)) {
                m.state = string(r);
            } else if ("entity_id".equals(name)) {
                m.entity = string(r);
            } else if ("attributes".equals(name) && r.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("brightness".equals(r.nextName()) && r.peek() == android.util.JsonToken.NUMBER) {
                        m.brightness = r.nextInt();
                        m.hasBrightness = true;
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
    }

    private static String string(JsonReader r) throws IOException {
        if (r.peek() == android.util.JsonToken.NULL) {
            r.nextNull();
            return "";
        }
        return r.nextString();
    }
}
