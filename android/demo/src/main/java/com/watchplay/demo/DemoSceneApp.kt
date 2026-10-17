package com.watchplay.demo

import com.watchplay.sdk.apps.MirrorApp
import com.watchplay.sdk.render.SceneUpdate
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.render.WatchScene
import com.watchplay.sdk.touch.MappedTouch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The demo's [MirrorApp]: the example scene plus the data it shows.
 *
 * ```
 *   pollUpdate()  ->  SceneUpdate(data = clock/battery/guidance)
 *                          |
 *                          v
 *   WatchPlayClient.postUpdate -> WebViewRenderModule.applyUpdate
 *                          |
 *                          v
 *   window.watchplay.data  ->  watchplay.render()  ->  DOM
 * ```
 *
 * The split matters: the scene owns the *look* and never computes the time, the battery or the distance —
 * those come from here, once per second. That is what `06-rendering.md` §3.7 asks for, because a capture
 * may fire at any moment and a frame must be a pure function of the state the phone announced.
 *
 * The guidance is a scripted route, not a routing SDK: `docs/spec/00-overview.md` keeps turn-by-turn
 * geometry on the phone and sends only an arrow, a distance and an ETA. Wiring a real
 * `Navigator`/`MediaBrowserService` is the host app's job, see `MapNavigationApp`/`MusicPlayerApp`.
 *
 * @property html the document read from `assets/demo-scene.html`, i.e. exactly the file the browser
 *   preview renders.
 * @property wallClock source of "what time is it"; injected so tests do not depend on the 24-hour cycle.
 * @property zone time zone for the clock and the date; injected for the same reason.
 */
class DemoSceneApp(
    private val html: String,
    private val music: DemoMusic? = null,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : MirrorApp {

    override val appId: String = ID

    override val displayName: String = "WatchPlay demo scene"

    /** Phone battery, `0..100`; `null` until a `ACTION_BATTERY_CHANGED` broadcast has been seen. */
    @Volatile
    var batteryPercent: Int? = null

    private var lastPushAtMs: Long = 0L
    private var routeStartedAtMs: Long = 0L

    /** Art last pushed into the scene; `null` means "never pushed in this session". */
    private var lastCoverPushed: String? = null

    override fun createScene(layout: WatchLayoutSpec): WatchScene = WatchScene(id = ID, html = html)

    /**
     * Returns the current state once per [PUSH_PERIOD_MS], `null` in between.
     *
     * `nowMs` is the client's monotonic clock and only paces the pushes; the displayed time comes from
     * [wallClock] because a monotonic clock has no time of day.
     */
    override fun pollUpdate(nowMs: Long): SceneUpdate? {
        if (lastPushAtMs != 0L && nowMs - lastPushAtMs < PUSH_PERIOD_MS) return null
        lastPushAtMs = nowMs
        if (routeStartedAtMs == 0L) routeStartedAtMs = nowMs

        val wall = wallClock()
        val guidance = guidanceAt(nowMs - routeStartedAtMs)
        val data = linkedMapOf(
            "clock" to quoted(clockText(wall)),
            "date" to quoted(dateText(wall)),
            "maneuver" to quoted(guidance.glyph),
            "distance" to quoted(guidance.distanceText),
            "road" to quoted(guidance.road),
            "remaining" to quoted(guidance.remainingKm),
            "eta" to quoted(guidance.etaMinutes.toString()),
        )
        batteryPercent?.let { data["battery"] = it.toString() }
        music?.let { player ->
            // Real playback state: the scene only draws what the phone reports (`00-overview.md` §6, audio
            // never travels to the watch).
            data["title"] = quoted(player.title)
            data["artist"] = quoted(player.artist)
            data["playing"] = player.isPlaying.toString()
            data["progress"] = String.format(Locale.ROOT, "%.3f", player.progress)
            // The art is kilobytes of base64 that change only when the track does, so it goes out once.
            // `applyUpdate` merges into `window.watchplay.data`, so the scene keeps the value; an empty
            // string is a real change, not a missing key, and clears it.
            val cover = player.cover.orEmpty()
            if (cover != lastCoverPushed) {
                data["cover"] = quoted(cover)
                lastCoverPushed = cover
            }
        }

        return SceneUpdate(
            sceneId = ID,
            data = data,
            javascript = "window.watchplay.render && window.watchplay.render();",
            // The scene changed on screen, so the next capture should not wait for the pipeline's tick.
            requestFrameAfterUpdate = true,
        )
    }

    /** The scene owns its buttons (page switching, play/pause), so the app never claims a touch. */
    override fun onTouch(touch: MappedTouch): Boolean = false

    /**
     * Drives playback for a control tapped inside the scene.
     *
     * Called from the WebView's JavaScript bridge, which is how a tap that reached the *scene* becomes a
     * media action: `dispatchTouch` gives the WebView a real `MotionEvent`, the scene turns the resulting
     * click into this call (`05-input.md` §2.4 keeps the touch path native; the command back is app-level).
     *
     * @param control `play`, `pause`, `next` or `prev`, as declared by `data-ctl` in the scene.
     */
    fun control(control: String) {
        when (control) {
            "play", "pause", "toggle" -> music?.toggle()
            "next" -> music?.next()
            "prev", "previous" -> music?.previous()
        }
    }

    override fun onStart(host: MirrorApp.Host) {
        // A fresh start restarts the scripted route, so the demo is repeatable.
        routeStartedAtMs = 0L
        lastPushAtMs = 0L
        // The document was just reloaded, so its copy of the art is gone with it.
        lastCoverPushed = null
    }

    override fun onStop() = Unit

    // ---------------------------------------------------------------- scripted guidance

    /**
     * One leg of the scripted route: what the watch shows until the distance runs out.
     *
     * @property meters distance to the end of this leg, which the demo counts down.
     * @property durationMs how long the leg takes, i.e. the time to cover [meters].
     */
    private data class Leg(
        val glyph: String,
        val road: String,
        val meters: Int,
        val durationMs: Long,
    )

    /** What the scene needs for one frame. */
    private data class Guidance(
        val glyph: String,
        val distanceText: String,
        val road: String,
        val remainingKm: String,
        val etaMinutes: Int,
    )

    private fun guidanceAt(elapsedMs: Long): Guidance {
        val cycle = LEGS.sumOf { it.durationMs }
        var offset = elapsedMs.mod(cycle)
        var index = 0
        while (index < LEGS.lastIndex && offset >= LEGS[index].durationMs) {
            offset -= LEGS[index].durationMs
            index++
        }
        val leg = LEGS[index]
        val fraction = (offset.toFloat() / leg.durationMs).coerceIn(0f, 1f)
        val distanceMeters = (leg.meters * (1f - fraction)).roundToInt().coerceAtLeast(0)
        val remainingMeters = distanceMeters + LEGS.drop(index + 1).sumOf { it.meters }
        return Guidance(
            glyph = leg.glyph,
            distanceText = "$distanceMeters 米",
            road = leg.road,
            remainingKm = String.format(Locale.ROOT, "%.1f", remainingMeters / 1000.0),
            // 15 km/h through city traffic: 250 m per minute.
            etaMinutes = (remainingMeters / 250.0).roundToInt().coerceAtLeast(1),
        )
    }

    private fun clockText(wallMillis: Long): String {
        val time = Instant.ofEpochMilli(wallMillis).atZone(zone)
        return String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
    }

    private fun dateText(wallMillis: Long): String {
        val time = Instant.ofEpochMilli(wallMillis).atZone(zone)
        // `DayOfWeek.value` is MONDAY=1 … SUNDAY=7; the table starts on Sunday.
        return "周${WEEKDAYS[time.dayOfWeek.value % 7]} ${time.monthValue} 月 ${time.dayOfMonth} 日"
    }

    private fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** Scene id, also the `SceneUpdate.sceneId` this app addresses. */
        const val ID: String = "demo.scene"

        /** One update per second: faster is invisible at the ~10 fps capture cadence. */
        const val PUSH_PERIOD_MS: Long = 1_000L

        private val WEEKDAYS = listOf("日", "一", "二", "三", "四", "五", "六")

        /**
         * A 2.4 km route: left into 深南大道, straight on 滨河大道, right into 益田路, arrive.
         *
         * The last leg has no distance to cover — it is the "you are here" card — so it is also the
         * shortest, and the whole cycle stays under 90 s for a demo.
         */
        private val LEGS = listOf(
            Leg("↰", "左转 · 深南大道", meters = 280, durationMs = 20_000L),
            Leg("↑", "直行 · 滨河大道", meters = 620, durationMs = 20_000L),
            Leg("↱", "右转 · 益田路", meters = 1_500, durationMs = 25_000L),
            Leg("◉", "到达 · 目的地", meters = 0, durationMs = 6_000L),
        )
    }
}
