package com.watchplay.demo

import com.watchplay.sdk.render.WatchLayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The demo app's data contract with the scene.
 *
 * `SceneUpdate.data` values are spliced into a JavaScript object literal, so a number must arrive unquoted
 * and a string must arrive quoted and escaped; `pollUpdate` must also pace itself, because the control
 * thread calls it four times a second and every update asks for a frame.
 */
class DemoSceneAppTest {

    private val layout = WatchLayoutSpec.round()

    /** 2026-10-17 is a Saturday; the epoch value is only the source of the injected clock. */
    private val fixedWall = 1_792_200_000_000L

    private fun app(wall: Long = fixedWall, battery: Int? = null) =
        DemoSceneApp(
            html = "<html></html>",
            wallClock = { wall },
            zone = ZoneId.of("Asia/Shanghai"),
        ).also { it.batteryPercent = battery }

    @Test
    fun `the scene is loaded for the app's own id`() {
        val app = DemoSceneApp("<html>scene</html>")

        val scene = app.createScene(layout)

        assertEquals(DemoSceneApp.ID, scene.id)
        assertEquals("<html>scene</html>", scene.html)
    }

    @Test
    fun `the first poll pushes immediately and the next one waits for the period`() {
        val app = app()

        assertTrue("the first frame must not wait a second", app.pollUpdate(1_000L) != null)
        assertNull(app.pollUpdate(1_500L))
        assertTrue(app.pollUpdate(2_000L) != null)
    }

    @Test
    fun `clock and date are formatted from the injected wall clock`() {
        val data = app().pollUpdate(1_000L)?.data ?: error("no update")

        assertEquals(expectClock(), data["clock"])
        assertTrue("date should be the Shanghai weekday: ${data["date"]}", data["date"] == "\"周六 10 月 17 日\"")
    }

    @Test
    fun `the battery arrives as a bare number so the scene can print it`() {
        val data = app(battery = 76).pollUpdate(1_000L)?.data ?: error("no update")

        assertEquals("76", data["battery"])
    }

    @Test
    fun `without a battery reading the key is omitted instead of pushed as zero`() {
        val data = app(battery = null).pollUpdate(1_000L)?.data ?: error("no update")

        assertNull(data["battery"])
    }

    @Test
    fun `every data value is a valid javascript literal`() {
        val data = app(battery = 76).pollUpdate(1_000L)?.data ?: error("no update")

        for ((key, value) in data) {
            val quoted = value.startsWith("\"") && value.endsWith("\"")
            val numeric = value.matches(Regex("""-?\d+(?:\.\d+)?"""))
            assertTrue("$key=$value is neither quoted nor numeric", quoted || numeric)
        }
    }

    @Test
    fun `the guidance counts down inside a leg and advances to the next one`() {
        val app = app()
        val first = app.pollUpdate(1_000L)?.data ?: error("no update")
        val later = app.pollUpdate(11_000L)?.data ?: error("no update")
        val nextLeg = app.pollUpdate(21_000L)?.data ?: error("no update")

        assertTrue("distance must fall: ${first["distance"]} then ${later["distance"]}",
            metresOf(first["distance"]!!) > metresOf(later["distance"]!!))
        assertEquals("\"左转 · 深南大道\"", first["road"])
        assertEquals("\"直行 · 滨河大道\"", nextLeg["road"])
    }

    @Test
    fun `the route wraps so the demo can run unattended`() {
        val app = app()
        app.pollUpdate(1_000L)
        val wrapped = app.pollUpdate(1_000L + 90_000L)?.data ?: error("no update")

        assertEquals("\"左转 · 深南大道\"", wrapped["road"])
    }

    @Test
    fun `the scene is asked to repaint and to hand the frame over`() {
        val update = app().pollUpdate(1_000L) ?: error("no update")

        assertEquals("window.watchplay.render && window.watchplay.render();", update.javascript)
        assertTrue(update.requestFrameAfterUpdate)
    }

    private fun metresOf(value: String): Int =
        Regex("""(\d+)""").find(value)?.groupValues?.get(1)?.toInt() ?: error("no metres in $value")

    private fun expectClock(): String {
        val time = java.time.Instant.ofEpochMilli(fixedWall).atZone(ZoneId.of("Asia/Shanghai"))
        return String.format("\"%02d:%02d\"", time.hour, time.minute)
    }
}
