package com.watchplay.sdk.apps

import com.watchplay.sdk.render.SceneUpdate
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.touch.MappedTouch
import com.watchplay.sdk.touch.TouchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The music face's data contract: a 0..1 progress fraction, a bare boolean, and icon glyphs that are safe
 * to assign with `textContent`.
 */
class MusicPlayerAppTest {

    private val layout = WatchLayoutSpec.round()
    private fun touch(x: Float, action: TouchAction = TouchAction.UP) =
        MappedTouch(action = action, cssX = x, cssY = 233f, pressure = 1f, pointerId = 0, timestampMs = 1L)

    @Test
    fun `progress is clamped to a fraction`() {
        val app = MusicPlayerApp()
        app.update("A", "B", isPlaying = true, progress = 4.2f)
        assertEquals(1f, app.progress, 0f)

        app.update("A", "B", isPlaying = true, progress = -3f)
        assertEquals(0f, app.progress, 0f)
    }

    @Test
    fun `booleans and numbers are pushed unquoted and strings are quoted`() {
        val app = MusicPlayerApp()
        app.update("夜航西飞", "林", isPlaying = true, progress = 0.38f)

        val data = app.pollUpdate(1_000L)?.data ?: error("no update")

        assertEquals("\"夜航西飞\"", data["title"])
        assertEquals("true", data["playing"])
        assertEquals("0.38", data["progress"])
        assertNull("nothing changed means no frame", app.pollUpdate(2_000L))
    }

    @Test
    fun `a third of the panel width skips, the middle toggles`() {
        val app = MusicPlayerApp()
        app.onStart(object : MirrorApp.Host {
            override val layout = WatchLayoutSpec.round()
            override fun postUpdate(update: SceneUpdate) = Unit
            override fun requestFrame() = Unit
            override fun sendControl(opcode: Int, vararg parameters: Int) = Unit
        })

        assertFalse("DOWN is not a decision yet", app.onTouch(touch(20f, TouchAction.DOWN)))
        assertTrue(app.onTouch(touch(20f)))
        assertTrue(app.onTouch(touch(233f)))
        assertEquals(true, app.isPlaying)
    }

    @Test
    fun `the scene assigns glyphs, not html entities, through textContent`() {
        val html = MusicPlayerApp().createScene(layout).html

        // `textContent` does not decode entities, so '&gt;&gt;' would be painted literally as &gt;&gt;.
        assertFalse(html.contains("textContent = (d.playing === 'true') ? '&gt;"))
        assertTrue(html.contains("(d.playing === 'true') ? '>>' : '>'"))
    }

    @Test
    fun `the scene carries the shape fallbacks the spec documents`() {
        val html = MusicPlayerApp().createScene(layout).html

        assertTrue(html.contains("<html lang=\"en\" data-screen-shape=\"round\">"))
        assertTrue(html.contains("html[data-screen-shape=\"square\"]{ --wp-safe-inset:4vmin; }"))
        assertTrue(html.contains("padding:var(--wp-safe-inset)"))
    }
}
