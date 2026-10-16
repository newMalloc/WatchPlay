package com.watchplay.sdk.touch

import com.watchplay.sdk.protocol.TouchFlags
import com.watchplay.sdk.render.WatchLayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinate mapping and sequence rules of `05-input.md` §2–§3 and `06-rendering.md` §4.
 *
 * The round-panel numbers come from the 466×466 reference hardware: centre `(232.5, 232.5)`,
 * inscribed radius 233 px, so a corner at `(0,0)` is `328.8 px` from the centre and therefore invalid.
 */
class TouchHandlerTest {

    private val round466 = WatchLayoutSpec.round(466, 466)
    private val handler = TouchHandler(round466, cssViewportWidthPx = 466f, cssViewportHeightPx = 466f)

    @Test
    fun `mapping is the identity in the one-to-one case`() {
        assertTrue(handler.isIdentityMapping)
        val point = handler.mapToCss(233f, 233f)
        assertEquals(233f, point.x, 0.001f)
        assertEquals(233f, point.y, 0.001f)
    }

    @Test
    fun `a centre down is dispatched as css pixels`() {
        val outcome = handler.handle(TouchEvent.down(233, 233), receivedAtMs = 0)
        assertTrue(outcome is TouchOutcome.Dispatch)
        val touch = (outcome as TouchOutcome.Dispatch).touch
        assertEquals(TouchAction.DOWN, touch.action)
        assertEquals(233f, touch.cssX, 0.001f)
        assertEquals(233f, touch.cssY, 0.001f)
        assertFalse(touch.clamped)
        assertTrue(handler.isSequenceActive)
    }

    @Test
    fun `a down outside the inscribed circle is dropped and suppresses the sequence`() {
        val outcome = handler.handle(TouchEvent.down(0, 0), receivedAtMs = 0)
        assertTrue(outcome is TouchOutcome.Dropped)
        assertEquals(TouchDropReason.DOWN_OUTSIDE_CIRCLE, (outcome as TouchOutcome.Dropped).reason)
        assertEquals(1L, handler.outOfCircleDowns)
        assertFalse(handler.isSequenceActive)
        assertTrue(handler.isSequenceSuppressed)

        // Everything until the next DOWN is ignored.
        val move = handler.handle(TouchEvent.move(233, 233), receivedAtMs = 10)
        assertEquals(TouchDropReason.SEQUENCE_SUPPRESSED, (move as TouchOutcome.Dropped).reason)
    }

    @Test
    fun `the watch's own out of circle marker is honoured`() {
        val event = TouchEvent.down(233, 233, flags = TouchFlags.OUTSIDE_CIRCLE)
        val outcome = handler.handle(event, receivedAtMs = 0)
        assertEquals(TouchDropReason.MARKED_OUTSIDE_CIRCLE, (outcome as TouchOutcome.Dropped).reason)
        assertTrue(handler.isSequenceSuppressed)
    }

    @Test
    fun `a move outside the circle is projected onto the tolerance band, never turned into an up`() {
        handler.handle(TouchEvent.down(233, 233), receivedAtMs = 0)
        val outcome = handler.handle(TouchEvent.move(0, 0), receivedAtMs = 16)
        assertTrue(outcome is TouchOutcome.Dispatch)
        val touch = (outcome as TouchOutcome.Dispatch).touch
        assertEquals(TouchAction.MOVE, touch.action)
        assertTrue(touch.clamped)
        assertTrue(touch.outsideCircle)
        // 0.98 * 233 = 228.34 px from the centre along the diagonal => about 71.05 px on each axis.
        assertEquals(71.05f, touch.cssX, 1.0f)
        assertEquals(71.05f, touch.cssY, 1.0f)
        // The sequence must still be open: a drag that leaves the panel is not a lift.
        assertTrue(handler.isSequenceActive)
    }

    @Test
    fun `a move without a down is dropped`() {
        val outcome = handler.handle(TouchEvent.move(233, 233), receivedAtMs = 0)
        assertEquals(TouchDropReason.SEQUENCE_NOT_STARTED, (outcome as TouchOutcome.Dropped).reason)
        assertEquals(1L, handler.orphanEvents)
    }

    @Test
    fun `up and cancel end the sequence`() {
        handler.handle(TouchEvent.down(233, 233), receivedAtMs = 0)
        handler.handle(TouchEvent.up(233, 233), receivedAtMs = 20)
        assertFalse(handler.isSequenceActive)
        val orphan = handler.handle(TouchEvent.up(233, 233), receivedAtMs = 40)
        assertEquals(TouchDropReason.SEQUENCE_NOT_STARTED, (orphan as TouchOutcome.Dropped).reason)
    }

    @Test
    fun `coordinates beyond the panel are clamped into the viewport`() {
        val rectangular = WatchLayoutSpec.rectangular(466, 466, insetPermille = 40)
        val rectHandler = TouchHandler(rectangular, cssViewportWidthPx = 466f, cssViewportHeightPx = 466f)
        val outcome = rectHandler.handle(TouchEvent.down(500, 233), receivedAtMs = 0)
        assertTrue(outcome is TouchOutcome.Dispatch)
        assertEquals(465f, (outcome as TouchOutcome.Dispatch).touch.cssX, 0.001f)
        assertTrue(rectHandler.clampedCoordinates >= 1L)
    }

    @Test
    fun `scaled mapping compensates a non one-to-one viewport`() {
        // A WebView laid out at 233 CSS pixels for a 466-pixel panel: every coordinate halves.
        val scaled = TouchHandler(round466, cssViewportWidthPx = 233f, cssViewportHeightPx = 233f)
        assertFalse(scaled.isIdentityMapping)
        val point = scaled.mapToCss(233f, 233f)
        assertEquals(116.5f, point.x, 0.01f)
        assertEquals(233f, scaled.toWatchPx(point.x, point.y).x, 0.05f)
    }

    @Test
    fun `a stuck down is recovered with a synthetic cancel after ten seconds`() {
        handler.handle(TouchEvent.down(233, 233), receivedAtMs = 1_000)
        assertNull(handler.checkStuck(nowMs = 1_000 + 10_000))
        assertTrue(handler.isSequenceActive)

        val cancel = handler.checkStuck(nowMs = 1_000 + 10_001)
        assertNotNull(cancel)
        assertEquals(TouchAction.CANCEL, cancel!!.action)
        assertFalse(handler.isSequenceActive)
        assertEquals(1L, handler.stuckTouchRecoveries)
    }

    @Test
    fun `resetSequence drops an in-flight gesture`() {
        handler.handle(TouchEvent.down(233, 233), receivedAtMs = 0)
        handler.resetSequence()
        assertFalse(handler.isSequenceActive)
        assertFalse(handler.isSequenceSuppressed)
    }

    @Test
    fun `round panel touches outside the circle are not valid, square panel touches are`() {
        assertFalse(handler.isInsideCircle(10f, 10f))
        assertTrue(handler.isInsideCircle(233f, 233f))
        val square = TouchHandler(WatchLayoutSpec.rectangular(466, 466))
        assertTrue(square.isInsideCircle(10f, 10f))
    }

    @Test
    fun `touch payload round trips through the fourteen byte layout`() {
        val original = TouchEvent(
            action = TouchAction.MOVE,
            pointerId = 1,
            x = 300,
            y = 400,
            pressure = 200,
            flags = TouchFlags.NEAR_EDGE,
            timestampMs = 0xABCDEF01L,
        )
        val decoded = TouchEvent.decode(original.encode())
        assertEquals(original.action, decoded.action)
        assertEquals(1, decoded.pointerId)
        assertEquals(300, decoded.x)
        assertEquals(400, decoded.y)
        assertEquals(200, decoded.pressure)
        assertEquals(TouchFlags.NEAR_EDGE, decoded.flags)
        assertEquals(0xABCDEF01L, decoded.timestampMs)
        // Reserved bytes MUST be zero.
        val bytes = original.encode()
        assertEquals(0, bytes[8].toInt())
        assertEquals(0, bytes[9].toInt())
    }
}
