package com.watchplay.demo

import com.watchplay.sdk.apps.MirrorApp
import com.watchplay.sdk.render.SceneUpdate
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.render.WatchScene
import com.watchplay.sdk.touch.MappedTouch

/**
 * The demo's [MirrorApp]: one HTML document, no data pushes, touches forwarded to the WebView.
 *
 * The scene switches pages on its own — the three pages carry buttons for the other two — so [onTouch]
 * returns `false` and the SDK turns the watch's `TOUCH` frame into a native `MotionEvent`
 * (`05-input.md` §2.4). Returning `true` would claim the event for the app and the WebView would never
 * see the tap.
 *
 * @property html the document read from `assets/demo-scene.html`, i.e. exactly the file the browser
 *   preview renders.
 */
class DemoSceneApp(private val html: String) : MirrorApp {

    override val appId: String = ID

    override val displayName: String = "WatchPlay demo scene"

    override fun createScene(layout: WatchLayoutSpec): WatchScene = WatchScene(id = ID, html = html)

    /**
     * The scene runs its own clock and reads nothing else, so there is never an update to push.
     *
     * Frames still flow: `MirrorPipeline` captures on its own cadence, and `requestFrameAfterUpdate` only
     * brings the next capture forward.
     */
    override fun pollUpdate(nowMs: Long): SceneUpdate? = null

    override fun onTouch(touch: MappedTouch): Boolean = false

    override fun onStart(host: MirrorApp.Host) = Unit

    override fun onStop() = Unit

    companion object {
        /** Scene id, also the `SceneUpdate.sceneId` an app would target. */
        const val ID: String = "demo.scene"
    }
}
