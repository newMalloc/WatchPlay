package com.watchplay.demo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scene → app command bridge.
 *
 * A tap arrives as one `onControl` call from JavaScript; the app must see exactly one command. An earlier
 * version named its constructor callback `onControl` too, so `onControl(control)` inside the method resolved
 * to the method itself: every tap recursed until the stack blew, the app never heard it, and play/pause
 * looked dead.
 */
class SceneBridgeTest {

    @Test
    fun `a command reaches the app exactly once`() {
        val received = mutableListOf<String>()

        SceneBridge { received += it }.onControl("play")

        assertEquals(listOf("play"), received)
    }

    @Test
    fun `every command is passed through verbatim`() {
        val received = mutableListOf<String>()
        val bridge = SceneBridge { received += it }

        bridge.onControl("prev")
        bridge.onControl("next")

        assertEquals(listOf("prev", "next"), received)
    }
}
