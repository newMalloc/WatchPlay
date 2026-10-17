package com.watchplay.sdk.apps

import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.touch.MappedTouch
import com.watchplay.sdk.touch.TouchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app seam of `MirrorApp`: what reaches the WebView, and in which form.
 *
 * `pollUpdate` runs on the control thread and its payload is spliced into a JavaScript object literal, so
 * escaping and the bare-vs-quoted distinction are correctness, not cosmetics.
 */
class MapNavigationAppTest {

    private val layout = WatchLayoutSpec.round()
    private fun touch(action: TouchAction = TouchAction.UP) =
        MappedTouch(action = action, cssX = 200f, cssY = 200f, pressure = 1f, pointerId = 0, timestampMs = 1L)

    @Test
    fun `the scene is loaded for the app's own id`() {
        val app = MapNavigationApp()

        assertEquals("${app.appId}.scene", app.createScene(layout).id)
    }

    @Test
    fun `an update is pushed once, then nothing changes`() {
        val app = MapNavigationApp()
        app.update(maneuver = "left", distanceText = "230 m", roadName = "深南大道", etaText = "09:41")

        val first = app.pollUpdate(1_000L)
        assertEquals("\"left\"", first?.data?.get("maneuver"))
        assertEquals("\"230 m\"", first?.data?.get("distance"))
        assertEquals("\"09:41\"", first?.data?.get("eta"))
        assertNull("an unchanged face must not ask for another frame", app.pollUpdate(2_000L))
    }

    @Test
    fun `quotes and backslashes are escaped for the javascript payload`() {
        val app = MapNavigationApp()
        app.update(maneuver = "right", distanceText = "1.2 km", roadName = "A\"1\\B", etaText = "10:00")

        val data = app.pollUpdate(1_000L)?.data ?: error("no update")

        assertEquals("\"A\\\"1\\\\B\"", data["road"])
    }

    @Test
    fun `the render call is addressed to the scene's global`() {
        val app = MapNavigationApp()
        app.update(maneuver = "straight", distanceText = "1 km", roadName = "G4", etaText = "09:55")

        val update = app.pollUpdate(1_000L)

        assertEquals("watchplay.render && watchplay.render();", update?.javascript)
        assertTrue("an update that does not map data would show the previous frame", update?.requestFrameAfterUpdate == true)
    }

    @Test
    fun `touches reach the WebView instead of being swallowed`() {
        val app = MapNavigationApp()

        assertFalse(app.onTouch(touch(TouchAction.DOWN)))
        assertFalse(app.onTouch(touch(TouchAction.UP)))
    }

    @Test
    fun `the scene follows the rendering contract`() {
        val html = MapNavigationApp().createScene(layout).html

        assertTrue("the phone overwrites data-screen-shape, but the scene still needs a browser default",
            html.contains("<html lang=\"en\" data-screen-shape=\"round\">"))
        assertTrue(html.contains("html[data-screen-shape=\"square\"]{ --wp-safe-inset:4vmin; }"))
        assertTrue("padding must come from the injected variable", html.contains("padding:var(--wp-safe-inset)"))
        assertFalse("no absolute pixels in a scene", Regex("""\b\d+(?:\.\d+)?px\b""").containsMatchIn(html))
    }
}
