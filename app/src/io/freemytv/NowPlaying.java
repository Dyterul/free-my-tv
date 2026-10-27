package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What any app is playing (title, artist or channel, the app), from the media session each app publishes for the remote's
 * play/pause and the Assistant: one way for music, videos, radio and live streams alike. Reading the sessions needs this
 * notification listener allowed (HomeMode does it while Home Settings' Now Playing widget is on); it never reads a
 * notification and asks the system to unbind it at once, so no process stays up for it.
 */
public class NowPlaying extends NotificationListenerService {

    @Override public void onListenerConnected() {
        requestUnbind();
    }

    private static final float[] STILL = {0.55f, 1, 0.75f}, PERIOD_MS = {170, 230, 200};

    /** The bars' width for text of this cap height (drawBars). */
    static float barsWidth(float cap) {
        return cap * 1.12f;
    }

    /** Three level bars before the title, standing on its baseline, `cap` tall: still, or bouncing like a level meter. */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, boolean moving, RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            float level = moving ? 0.3f + 0.7f * Math.abs((float) Math.sin(t / PERIOD_MS[i] + i * 1.3f)) : STILL[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    /** The song that plays: its title, its artist, the app, and its cover (null when the app has none). */
    static final class Track {
        final String title, artist, app;
        final Bitmap art;

        Track(String title, String artist, String app, Bitmap art) {
            this.title = title;
            this.artist = artist;
            this.app = app;
            this.art = art;
        }

        /** "Title · Artist · App", the line the home screen shows. */
        String text() {
            StringBuilder s = new StringBuilder(title);
            if (!artist.isEmpty()) s.append(s.length() == 0 ? "" : " · ").append(artist);
            if (!app.isEmpty() && !app.equals(title) && !app.equals(artist)) {
                s.append(s.length() == 0 ? "" : " · ").append(app);
            }
            return s.length() == 0 ? null : s.toString();
        }

        /** The same song (the cover object too: a new song may bring a new one). */
        boolean sameAs(Track o) {
            return o != null && title.equals(o.title) && artist.equals(o.artist) && app.equals(o.app) && art == o.art;
        }
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Whether Android lets us read the sessions: our listener is allowed. */
    static boolean allowed(Context c) {
        try {
            c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean asked;

    /** A screen's watch: start() when it shows, stop() when it's hidden. text is null while nothing plays. */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final Map<String, String> labels = new HashMap<>();   // some players report every second
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
            @Override public void onSessionDestroyed() { update(); }
        };
        private boolean on;
        volatile String text;
        /** What plays now: the song's title, artist, app and cover (null for a video or a stream). */
        volatile Track track;


        Watch(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        void start() {
            if (on || !Prefs.nowPlaying()) return;   // off in Home Settings: nothing watched at all
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                // On, but not allowed (it was on when Original or the watchdog took it back): asked once, as Free My TV
                // mode's switch itself never allows it. A TV that refused it shows the row unavailable instead.
                if (!asked && Capabilities.adb(context) && !Capabilities.refused(context, Capabilities.LISTENER)) {
                    asked = true;
                    HomeMode.nowPlayingAccess(context);
                }
            }
        }

        void stop() {
            if (!on) return;
            on = false;
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (MediaController mc : controllers) mc.unregisterCallback(callback);
            controllers.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    mc.registerCallback(callback, handler);
                    controllers.add(mc);
                }
            }
            update();
        }

        private void update() {
            Track now = null;
            for (MediaController mc : controllers) {   // most recent first
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                // A stream that buffers is still playing: the line doesn't blink back to the prices.
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING) {
                    now = describe(mc);
                    break;
                }
            }
            if (now == null ? track == null : now.sameAs(track)) return;
            track = now;
            text = now == null ? null : now.text();
            changed.run();
        }

        /** The title, the artist, the app and the cover (music); null when the app says nothing at all. */
        private Track describe(MediaController mc) {
            MediaMetadata m = mc.getMetadata();
            MediaDescription d = m == null ? null : m.getDescription();
            String title = d == null || TextUtils.isEmpty(d.getTitle()) ? "" : d.getTitle().toString().trim();
            String artist = d == null || TextUtils.isEmpty(d.getSubtitle()) ? "" : d.getSubtitle().toString().trim();
            String app = labels.get(mc.getPackageName());
            if (app == null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    app = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = "";   // an app without a launcher icon is hidden from us (Android 11+): no name to show
                }
                labels.put(mc.getPackageName(), app);
            }
            if (title.isEmpty() && artist.isEmpty() && app.isEmpty()) return null;
            Bitmap art = m == null ? null : m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null && m != null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            return new Track(title, artist, app, art);
        }
    }
}
