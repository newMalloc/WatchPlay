package com.watchplay.sdk.apps

import com.watchplay.sdk.render.SceneUpdate
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.render.WatchScene
import com.watchplay.sdk.touch.MappedTouch

/**
 * Turn-by-turn navigation on the watch face.
 *
 * ## Status: skeleton with a real scene
 *
 * The scene, the data model and the update path work; the navigation data itself does not come from
 * anywhere yet. Wiring it up is the job of the next contributor and is described below.
 *
 * ## How to feed it real data (Google Maps / any routing SDK)
 *
 * The phone app owns the SDK integration, not this class:
 *
 *  1. Add `com.google.android.gms:play-services-maps` (for map tiles on the phone's own screen) and,
 *     for real guidance, the **Navigation SDK for Android** (`com.google.android.libraries.navigation`),
 *     which requires a Google Cloud project, a Maps API key in the manifest, and an accepted
 *     Navigation SDK licence. Neither is a dependency of `:sdk`, deliberately: a library must not drag
 *     in a paid SDK or force a licence on its consumers.
 *  2. Create a `Navigator` and a `NavigatorListener`; on each `onGuidanceUpdated`, map the SDK's
 *     `GuidanceState` onto [update] (manoeuvre type, distance to the next step, ETA, road name).
 *  3. Because a watch has no network path of its own, the phone resolves all geometry and sends only
 *     what the watch must draw: an arrow glyph, a distance string and an ETA string. Do not try to
 *     render map tiles on the watch — at 10 fps MJPEG that would be an order of magnitude over budget
 *     (`00-overview.md` §2).
 *  4. Maneuver arrows should be pure CSS or inline SVG: icon fonts are not guaranteed to exist on the
 *     watch's WebView, and a missing glyph renders as a tofu box (`06-rendering.md` §6 note).
 *
 * ## Layout rules honoured by the scene
 *
 *  * The arrow sits at `r <= 0.25·S` from the centre and distance text inside the central 70% band,
 *    per `06-rendering.md` §5.1 — on a round panel the bottom of the safe area is much narrower than
 *    the middle.
 *  * Every size is `vmin`-relative and the padding comes from `var(--wp-safe-inset)`.
 */
class MapNavigationApp : MirrorApp {

    override val appId: String = "com.watchplay.apps.map"

    override val displayName: String = "Navigation"

    /** Current manoeuvre glyph: `left`, `right`, `straight`, `uturn` or `arrive`. */
    @Volatile
    var maneuver: String = "straight"
        private set

    /** Distance to the next manoeuvre, already formatted, e.g. `"230 m"`. */
    @Volatile
    var distanceText: String = "--"
        private set

    /** Road name for the next manoeuvre. */
    @Volatile
    var roadName: String = "Waiting for route"
        private set

    /** Estimated arrival, already formatted, e.g. `"09:41"`. */
    @Volatile
    var etaText: String = "--:--"
        private set

    private var host: MirrorApp.Host? = null
    private var dirty = true
    private var sceneId = "$appId.scene"

    /**
     * Replaces the guidance shown on the watch.
     *
     * Kept as a plain setter so that any routing source — Google's Navigation SDK, Mapbox, a bicycle
     * computer over BLE, a test — can drive it without the SDK depending on that source.
     */
    fun update(
        maneuver: String,
        distanceText: String,
        roadName: String,
        etaText: String,
    ) {
        this.maneuver = maneuver
        this.distanceText = distanceText
        this.roadName = roadName
        this.etaText = etaText
        dirty = true
    }

    override fun createScene(layout: WatchLayoutSpec): WatchScene = WatchScene(
        id = sceneId,
        html = buildHtml(),
    )

    override fun pollUpdate(nowMs: Long): SceneUpdate? {
        if (!dirty) return null
        dirty = false
        return SceneUpdate(
            sceneId = sceneId,
            data = linkedMapOf(
                "maneuver" to "\"${maneuver.escapeJson()}\"",
                "distance" to "\"${distanceText.escapeJson()}\"",
                "road" to "\"${roadName.escapeJson()}\"",
                "eta" to "\"${etaText.escapeJson()}\"",
            ),
            javascript = "watchplay.render && watchplay.render();",
        )
    }

    override fun onTouch(touch: MappedTouch): Boolean {
        // The nav face has no controls of its own: hands the tap to the WebView, where a scene's buttons
        // live (`05-input.md` §2.4). A tap is also not a stats request — `CONTROL.REPORT_STATS` is what
        // the host app sends on its own schedule, and answering a touch with it would swallow the tap.
        return false
    }

    override fun onStart(host: MirrorApp.Host) {
        this.host = host
        dirty = true
        // TODO(next contributor): start the Navigation SDK session here and register the
        // NavigatorListener that calls update(...) on every guidance change.
    }

    override fun onStop() {
        host = null
        // TODO(next contributor): stop the Navigator and release the Maps API client.
    }

    private fun buildHtml(): String = """
        <!doctype html>
        <html lang="en" data-screen-shape="round">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <title>WatchPlay navigation</title>
        <style>
        :root{ --wp-safe-inset:14vmin; --wp-bg:#05070c; --wp-fg:#eef3ff; --wp-dim:#8a93a8; --wp-accent:#4ac6ff; }
        html[data-screen-shape="square"]{ --wp-safe-inset:4vmin; }
        *{ box-sizing:border-box; margin:0; padding:0; }
        html,body{ width:100%; height:100%; overflow:hidden; background:var(--wp-bg); color:var(--wp-fg);
          font-family:system-ui,"Roboto","Noto Sans CJK SC",sans-serif; -webkit-user-select:none; user-select:none;
          -webkit-tap-highlight-color:transparent; }
        .safe{ height:100%; padding:var(--wp-safe-inset); display:flex; flex-direction:column;
          align-items:center; justify-content:center; gap:2vmin; }
        /* The arrow is pure CSS: an icon font is not guaranteed to exist on the watch. */
        .arrow{ width:34vmin; height:34vmin; position:relative; }
        .arrow i{ position:absolute; inset:0; margin:auto; width:9vmin; height:24vmin; background:var(--wp-accent);
          border-radius:2vmin; }
        .arrow i::after{ content:""; position:absolute; left:-6vmin; top:-2vmin; border-left:10.5vmin solid transparent;
          border-right:10.5vmin solid transparent; border-bottom:14vmin solid var(--wp-accent); }
        html[data-maneuver="left"] .arrow{ transform:rotate(-90deg); }
        html[data-maneuver="right"] .arrow{ transform:rotate(90deg); }
        html[data-maneuver="uturn"] .arrow{ transform:rotate(180deg); }
        .distance{ font-size:12.3vmin; font-weight:600; line-height:1.1; }
        .road{ font-size:5.6vmin; color:var(--wp-fg); max-width:88%; text-align:center;
          white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .eta{ font-size:4.3vmin; color:var(--wp-dim); }
        </style>
        </head>
        <body>
          <main class="safe">
            <div class="arrow" aria-hidden="true"><i></i></div>
            <div class="distance" id="distance">--</div>
            <div class="road" id="road">Waiting for route</div>
            <div class="eta" id="eta">ETA --:--</div>
          </main>
        <script>
          window.watchplay = window.watchplay || {};
          window.watchplay.render = function () {
            var d = window.watchplay.data || {};
            document.documentElement.dataset.maneuver = d.maneuver || 'straight';
            document.getElementById('distance').textContent = d.distance || '--';
            document.getElementById('road').textContent = d.road || '';
            document.getElementById('eta').textContent = 'ETA ' + (d.eta || '--:--');
          };
        </script>
        </body>
        </html>
    """.trimIndent()

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}
