package com.watchplay.demo

/**
 * The music page's contract with the scene.
 *
 * `00-overview.md` §6 keeps audio **on the phone**: the watch has no A2DP path over BLE, so it only draws
 * the controls and the progress. This interface is what `DemoSceneApp` pushes into the scene and what the
 * scene's buttons drive, which keeps the media stack out of the mirroring code.
 */
interface DemoMusic {

    /** `true` once a track list is loaded and at least one track can be played. */
    val available: Boolean

    /** Current track title, already suitable for display. */
    val title: String

    /** Current track artist. */
    val artist: String

    /** `true` while audio is playing. */
    val isPlaying: Boolean

    /** Playback position as a fraction of the track duration, `0..1`. */
    val progress: Float

    /**
     * Album art for the current track as a `data:` URI, or `null` when it has none.
     *
     * A `data:` URI rather than a `content://` one because the scene runs under
     * `blockNetworkLoads = true` (`06-rendering.md` §3.2) and cannot fetch anything: the phone fetches
     * the picture, shrinks it to a thumbnail and hands the bytes over. The same rule `06-rendering.md`
     * §3.6 states for fonts.
     */
    val cover: String?

    /** Why there is nothing to play when [available] is `false`; empty when it is. */
    val statusText: String

    /** Play or pause the current track. */
    fun toggle()

    /** Skip to the next track. */
    fun next()

    /** Go back to the previous track. */
    fun previous()
}
