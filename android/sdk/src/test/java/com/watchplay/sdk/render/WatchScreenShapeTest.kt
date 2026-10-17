package com.watchplay.sdk.render

import com.watchplay.sdk.protocol.ScreenShape
import com.watchplay.sdk.render.WatchLayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel outline a phone-side preview clips to.
 *
 * The rules come from `06-rendering.md` §2.2 (the panel clips, not the scene) and §2.4 (the reported
 * inset is what a rounded panel keeps clear of its corner), plus `07-capabilities.md` §4.1 (an unknown
 * panel must not be guessed round).
 */
class WatchScreenShapeTest {

    @Test
    fun `a square round panel is a circle of half its side`() {
        val layout = WatchLayoutSpec.round(466)

        assertTrue(WatchScreenShape.isCircular(layout))
        assertTrue(WatchScreenShape.hasClippedCorners(layout))
        assertEquals(233f, WatchScreenShape.cornerRadiusPx(layout), 0.01f)
    }

    @Test
    fun `a rounded rect uses the inset the watch reported`() {
        // 410 wide, 40‰ of the shorter side = 16.4 px, the figure 03-session.md quotes for that panel.
        val layout = WatchLayoutSpec.rectangular(
            widthPx = 410,
            heightPx = 502,
            shape = ScreenShape.ROUNDED_RECT,
        )

        assertFalse(WatchScreenShape.isCircular(layout))
        assertEquals(16.4f, WatchScreenShape.cornerRadiusPx(layout), 0.01f)
        assertTrue(WatchScreenShape.hasClippedCorners(layout))
    }

    @Test
    fun `a rectangular panel keeps its corners sharp`() {
        val layout = WatchLayoutSpec.rectangular(466, 466, shape = ScreenShape.RECTANGULAR)

        assertEquals(0f, WatchScreenShape.cornerRadiusPx(layout), 0f)
        assertFalse(WatchScreenShape.hasClippedCorners(layout))
    }

    @Test
    fun `an unknown panel is not guessed round`() {
        val layout = WatchLayoutSpec.rectangular(466, 466, shape = ScreenShape.UNKNOWN)

        assertFalse(WatchScreenShape.isCircular(layout))
        assertEquals(0f, WatchScreenShape.cornerRadiusPx(layout), 0f)
    }

    @Test
    fun `a round panel reporting unequal sides becomes a stadium, not a corner`() {
        val layout = WatchLayoutSpec.round(widthPx = 410, heightPx = 502)

        assertFalse("a non-square panel is not a circle", WatchScreenShape.isCircular(layout))
        assertEquals(205f, WatchScreenShape.cornerRadiusPx(layout), 0.01f)
        assertTrue(WatchScreenShape.hasClippedCorners(layout))
    }

    @Test
    fun `the largest legal inset still leaves a corner inside the panel`() {
        // 499‰ is the protocol maximum (`06-rendering.md` §2.3), so a rounded-rect can never be a circle.
        val layout = WatchLayoutSpec(
            shape = ScreenShape.ROUNDED_RECT,
            widthPx = 100,
            heightPx = 100,
            safeInsetPermille = 499,
        )

        assertEquals(49.9f, WatchScreenShape.cornerRadiusPx(layout), 0.01f)
        assertFalse(WatchScreenShape.isCircular(layout))
    }

    @Test
    fun `describe names the shape and the radius`() {
        assertEquals("round 466x466, circle", WatchScreenShape.describe(WatchLayoutSpec.round()))
        assertEquals(
            "rounded_rect 410x502, radius=16.4px",
            WatchScreenShape.describe(
                WatchLayoutSpec.rectangular(410, 502, shape = ScreenShape.ROUNDED_RECT),
            ),
        )
    }
}
