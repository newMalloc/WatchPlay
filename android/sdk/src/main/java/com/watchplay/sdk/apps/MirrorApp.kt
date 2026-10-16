package com.watchplay.sdk.apps

import com.watchplay.sdk.render.SceneUpdate
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.render.WatchScene
import com.watchplay.sdk.touch.MappedTouch

/**
 * A watch-screen "application": one scene plus the data and touch handling that make it live.
 *
 * ## Why this interface exists
 *
 * The SDK deliberately knows nothing about *what* is being mirrored. `MirrorApp` is the seam where an
 * app plugs its content in, and it is small on purpose:
 *
 * ```
 *   createScene(layout)  -> the HTML+CSS document, authored for the watch's geometry
 *   pollUpdate(nowMs)    -> new data, or null when nothing changed
 *   onTouch(touch)       -> react to a tap, or return false to let the WebView handle it
 *   onStart/onStop(host) -> acquire and release app resources (Map SDK, MediaSession, ...)
 * ```
 *
 * ## Rules that come from the specification
 *
 *  * The scene **must** be self-contained: no network fonts, no external resources, no `<video>`, no
 *    web framework runtime, and no absolute pixel sizes (`06-rendering.md` §3.1, §3.8).
 *  * Sizes are relative units; the safe-area padding comes from `var(--wp-safe-inset)`, which the phone
 *    injects from the watch's own `safe_inset_permille` (`06-rendering.md` §2.3 R1).
 *  * One document must serve both round and square panels by reading
 *    `html[data-screen-shape]` — never ship two documents (`06-rendering.md` §3.4).
 *  * Animations faster than 100 ms are invisible at the ~10 fps capture cadence and only add jitter
 *    (`06-rendering.md` §3.7).
 */
interface MirrorApp {

    /** Stable identifier, e.g. `com.watchplay.apps.map`. */
    val appId: String

    /** Human-readable name for logs and pickers. */
    val displayName: String

    /**
     * Builds the scene for [layout].
     *
     * Called when the app starts and whenever the panel geometry changes. Implementations should be
     * pure and fast: reloading a document costs at least one frame.
     */
    fun createScene(layout: WatchLayoutSpec): WatchScene

    /**
     * Returns new data for the scene, or `null` when nothing changed.
     *
     * Called on the control thread at a modest rate (once per second by default). Returning an update
     * with `requestFrameAfterUpdate = true` is what makes the pipeline capture a fresh frame, so an app
     * that never returns an update will show a frozen image.
     */
    fun pollUpdate(nowMs: Long): SceneUpdate?

    /**
     * Handles a mapped touch.
     *
     * @return `true` when the app consumed the event; `false` lets the SDK forward it into the WebView
     *   as a native `MotionEvent` (`05-input.md` §2.4), which is what a CSS/JS-driven scene wants.
     */
    fun onTouch(touch: MappedTouch): Boolean

    /** Called once when the app becomes active; acquire SDK clients here. */
    fun onStart(host: Host)

    /** Called when the app stops; release everything acquired in [onStart]. */
    fun onStop()

    /**
     * The services the SDK offers an app.
     *
     * Deliberately narrow: an app can push an update, ask for a frame, and send a `CONTROL` command. It
     * cannot touch the transport, the encoder or the session, so an app can never corrupt the protocol.
     */
    interface Host {

        /** Geometry of the panel currently being mirrored. */
        val layout: WatchLayoutSpec

        /** Queues a scene update for the WebView. */
        fun postUpdate(update: SceneUpdate)

        /** Requests a capture as soon as the WebView has painted. */
        fun requestFrame()

        /**
         * Sends a `CONTROL` command (`03-session.md` §5).
         *
         * @param opcode a `ControlCommand` value.
         * @param parameters up to a few `u8` parameters, depending on the opcode.
         */
        fun sendControl(opcode: Int, vararg parameters: Int)
    }
}
