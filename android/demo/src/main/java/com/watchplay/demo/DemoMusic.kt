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

    /** Why there is nothing to play when [available] is `false`; empty when it is. */
    val statusText: String

    /** Play or pause the current track. */
    fun toggle()

    /** Skip to the next track. */
    fun next()

    /** Go back to the previous track. */
    fun previous()
}
