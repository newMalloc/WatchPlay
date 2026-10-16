package com.watchplay.sdk.apps

import com.watchplay.sdk.render.SceneUpdate
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.render.WatchScene
import com.watchplay.sdk.touch.MappedTouch
import com.watchplay.sdk.touch.TouchAction

/**
 * A music player face with cover art, title/artist and an arc progress ring.
 *
 * ## Status: skeleton with a real scene
 *
 * The scene, its data model and the touch handling work; the media session is not wired up.
 *
 * ## How to feed it real data (`MediaSession` / `MediaBrowserService`)
 *
 * Audio plays **on the phone**; the watch only mirrors the controls
 * (`00-overview.md` §6: the ESP32-S3 has no classic Bluetooth and therefore no A2DP, and
 * `03-session.md` §3.7 expects `AUDIO.path = 0`).
 *
 *  1. In the host app, own a `MediaBrowserServiceCompat` (or `MediaSessionService`) whose
 *     `MediaSessionCompat.Callback` implements play/pause/skip. `:sdk` does not depend on
 *     `androidx.media`, on purpose.
 *  2. Observe `MediaMetadataCompat` (`METADATA_KEY_TITLE`, `_ARTIST`, `_DURATION`) and
 *     `PlaybackStateCompat` (`STATE`, `position`, `speed`) and call [update] from that observer.
 *  3. Cover art: downscale to a square of at most 96×96 and inline it as a `data:` URI — the scene must
 *     make **zero** network requests (`06-rendering.md` §3.8). A 466×466 JPEG of album art would also
 *     blow the bandwidth budget for a decorative element.
 *  4. Progress is pushed as a 0..1 fraction and rendered with `conic-gradient`, which the capture path
 *     handles as a plain paint. Do **not** animate it in CSS: the phone drives the phase per frame so
 *     that the captured frame and its timestamp always agree (`06-rendering.md` §3.7).
 *  5. Layout: the cover is a circle at `r <= 0.30·S`, the title stays inside the central 70% band, and
 *     the control row holds **two** 7 mm targets — three would reach the narrow part of a round panel
 *     (`06-rendering.md` §5.1, §6.1).
 */
class MusicPlayerApp : MirrorApp {

    override val appId: String = "com.watchplay.apps.music"

    override val displayName: String = "Music"

    /** Track title. */
    @Volatile
    var title: String = "Nothing playing"
        private set

    /** Track artist. */
    @Volatile
    var artist: String = "WatchPlay"
        private set

    /** `true` while audio is playing. */
    @Volatile
    var isPlaying: Boolean = false
        private set

    /** Playback position as a fraction of the track duration, `0.0..1.0`. */
    @Volatile
    var progress: Float = 0f
        private set

    /** Optional `data:` URI for the cover; `null` falls back to a CSS gradient. */
    @Volatile
    var coverArtDataUri: String? = null
        private set

    private var host: MirrorApp.Host? = null
    private var dirty = true
    private val sceneId = "$appId.scene"

    /** Replaces the displayed track state. Intended to be called from a media-session observer. */
    fun update(
        title: String,
        artist: String,
        isPlaying: Boolean,
        progress: Float,
        coverArtDataUri: String? = null,
    ) {
        this.title = title
        this.artist = artist
        this.isPlaying = isPlaying
        this.progress = progress.coerceIn(0f, 1f)
        this.coverArtDataUri = coverArtDataUri
        dirty = true
    }

    override fun createScene(layout: WatchLayoutSpec): WatchScene = WatchScene(
        id = sceneId,
        html = buildHtml(),
    )

    override fun pollUpdate(nowMs: Long): SceneUpdate? {
        if (!dirty) return null
        dirty = false
        val data = linkedMapOf(
            "title" to "\"${title.escapeJson()}\"",
            "artist" to "\"${artist.escapeJson()}\"",
            "playing" to isPlaying.toString(),
            "progress" to progress.toString(),
        )
        // A tiny progress change is not worth a frame: only repaint on a visible step.
        return SceneUpdate(
            sceneId = sceneId,
            data = data,
            javascript = "watchplay.render && watchplay.render();",
        )
    }

    override fun onTouch(touch: MappedTouch): Boolean {
        if (touch.action != TouchAction.UP) return false
        // Centre tap toggles playback; the left/right thirds skip. The scene keeps 7 mm targets, so the
        // phone must not invent extra hit slop (`06-rendering.md` §4).
        val width = host?.layout?.widthPx ?: return false
        val third = width / 3f
        when {
            touch.cssX < third -> previous()
            touch.cssX > width - third -> next()
            else -> togglePlayPause()
        }
        return true
    }

    /** Toggles playback; the host app is expected to observe [isPlaying] via its media session. */
    fun togglePlayPause() {
        isPlaying = !isPlaying
        dirty = true
        // TODO(next contributor): forward to the host app's MediaSessionCompat.TransportControls.
    }

    /** Requests the next track. */
    fun next() {
        dirty = true
        // TODO(next contributor): TransportControls.skipToNext().
    }

    /** Requests the previous track. */
    fun previous() {
        dirty = true
        // TODO(next contributor): TransportControls.skipToPrevious().
    }

    override fun onStart(host: MirrorApp.Host) {
        this.host = host
        dirty = true
        // TODO(next contributor): connect to the host app's MediaBrowserServiceCompat and register the
        // MediaMetadataCompat / PlaybackStateCompat observers that call update(...).
    }

    override fun onStop() {
        host = null
        // TODO(next contributor): unregister the observers.
    }

    private fun buildHtml(): String = """
        <!doctype html>
        <html lang="en" data-screen-shape="round">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <title>WatchPlay music</title>
        <style>
        :root{ --wp-safe-inset:14vmin; --wp-bg:#05070c; --wp-fg:#eef3ff; --wp-dim:#8a93a8; --wp-accent:#4ac6ff;
          --wp-line:rgba(255,255,255,.10); --wp-progress:0; --hit:19.3vmin; }
        html[data-screen-shape="square"]{ --wp-safe-inset:4vmin; }
        *{ box-sizing:border-box; margin:0; padding:0; }
        html,body{ width:100%; height:100%; overflow:hidden; background:var(--wp-bg); color:var(--wp-fg);
          font-family:system-ui,"Roboto","Noto Sans CJK SC",sans-serif; font-variant-numeric:tabular-nums;
          -webkit-user-select:none; user-select:none; -webkit-tap-highlight-color:transparent; }
        .safe{ height:100%; padding:var(--wp-safe-inset); display:flex; flex-direction:column;
          align-items:center; justify-content:center; gap:1.5vmin; }
        .dial{ position:relative; width:36vmin; height:36vmin; }
        .ring{ position:absolute; inset:0; border-radius:50%;
          background:conic-gradient(var(--wp-accent) calc(var(--wp-progress) * 1turn), var(--wp-line) 0);
          -webkit-mask:radial-gradient(circle,#0000 0 79.9%,#000 80%); mask:radial-gradient(circle,#0000 0 79.9%,#000 80%); }
        .cover{ position:absolute; inset:2.6vmin; border-radius:50%;
          background:linear-gradient(145deg,#1d2740,#0a0d16 70%); background-size:cover; background-position:center; }
        .title{ font-size:7.3vmin; font-weight:600; line-height:1.1; max-width:88%; text-align:center;
          white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .artist{ font-size:4.3vmin; color:var(--wp-dim); line-height:1.1; }
        .state{ font-size:4.3vmin; color:var(--wp-accent); }
        </style>
        </head>
        <body>
          <main class="safe">
            <section class="dial" aria-label="album art">
              <div class="ring" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0" id="ring"></div>
              <div class="cover" id="cover"></div>
            </section>
            <h1 class="title" id="title">Nothing playing</h1>
            <p class="artist" id="artist">WatchPlay</p>
            <p class="state" id="state">&gt;&gt;</p>
          </main>
        <script>
          window.watchplay = window.watchplay || {};
          window.watchplay.render = function () {
            var d = window.watchplay.data || {};
            document.getElementById('title').textContent = d.title || '';
            document.getElementById('artist').textContent = d.artist || '';
            document.getElementById('state').textContent = (d.playing === 'true') ? '&gt;&gt;' : '&gt;';
            var p = parseFloat(d.progress || '0');
            document.documentElement.style.setProperty('--wp-progress', String(isFinite(p) ? p : 0));
            document.getElementById('ring').setAttribute('aria-valuenow', String(Math.round((isFinite(p) ? p : 0) * 100)));
            if (d.cover) document.getElementById('cover').style.backgroundImage = 'url(' + d.cover + ')';
          };
        </script>
        </body>
        </html>
    """.trimIndent()

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}
