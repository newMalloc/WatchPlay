package com.watchplay.sdk.pipeline

import com.watchplay.sdk.protocol.Feedback
import com.watchplay.sdk.protocol.FeedbackFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `04-video.md` §6 loop, one rule per test.
 *
 * Time is an explicit argument everywhere, so every assertion is about the rule and not about how fast
 * the machine happens to run. The two properties worth stating once: quality never leaves `[40, 85]` and
 * frame rate never leaves `[1, negotiated]`, because §6 calls them hard constraints.
 */
class RateControlTest {

    private fun feedback(
        queueDepth: Int = 0,
        framesDropped: Long = 0L,
        decodeMsAvg: Int = 0,
        credits: Long = 64L,
        overloaded: Boolean = false,
    ) = Feedback(
        credits = credits,
        framesDropped = framesDropped,
        decodeMsAvg = decodeMsAvg,
        queueDepth = queueDepth,
        flags = if (overloaded) FeedbackFlags.OVERLOADED else 0,
    )

    @Test
    fun `startup runs three frames at 80 percent of the steady quality`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        assertEquals(56, control.effectiveQuality)
        repeat(2) { control.onFrameSent() }
        assertEquals("frame 3 is still inside the startup window", 56, control.effectiveQuality)
        control.onFrameSent()
        assertEquals("frame 4 is the steady state", 70, control.effectiveQuality)
    }

    @Test
    fun `sustained queue depth costs one quality step per second`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        control.onFeedback(feedback(queueDepth = 5), rttMs = 0, nowMs = 0)
        assertEquals("a single backed-up report is not yet sustained", 70, control.quality)

        control.onFeedback(feedback(queueDepth = 5), rttMs = 0, nowMs = 500)
        assertEquals(70, control.quality)

        control.onFeedback(feedback(queueDepth = 5), rttMs = 0, nowMs = 1_000)
        assertEquals(62, control.quality)

        control.onFeedback(feedback(queueDepth = 5), rttMs = 0, nowMs = 1_100)
        assertEquals("the 1 s cooldown holds", 62, control.quality)

        control.onFeedback(feedback(queueDepth = 5), rttMs = 0, nowMs = 2_000)
        assertEquals(54, control.quality)
    }

    @Test
    fun `a queue that drains resets the sustained timer`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        control.onFeedback(feedback(queueDepth = 5), rttMs = 0, nowMs = 0)
        control.onFeedback(feedback(queueDepth = 5), rttMs = 0, nowMs = 900)
        control.onFeedback(feedback(queueDepth = 0), rttMs = 0, nowMs = 950)
        control.onFeedback(feedback(queueDepth = 5), rttMs = 0, nowMs = 1_000)
        assertEquals("the second run of pressure has to last a full second", 70, control.quality)

        control.onFeedback(feedback(queueDepth = 5), rttMs = 0, nowMs = 2_000)
        assertEquals(62, control.quality)
    }

    @Test
    fun `more than ten drops in five seconds costs ten quality points immediately`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        control.onFeedback(feedback(framesDropped = 3), rttMs = 0, nowMs = 0)
        assertEquals(70, control.quality)

        control.onFeedback(feedback(framesDropped = 14), rttMs = 0, nowMs = 1_000)
        assertEquals(60, control.quality)

        control.onFeedback(feedback(framesDropped = 14), rttMs = 0, nowMs = 1_050)
        assertEquals("the counter is not cumulative across the same drop", 60, control.quality)
    }

    @Test
    fun `slow decoding costs frame rate, not quality`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        // 80 % of a 100 ms frame interval is 80 ms; 90 ms is already too slow.
        control.onFeedback(feedback(decodeMsAvg = 90), rttMs = 0, nowMs = 0)
        assertEquals(8, control.targetFps)
        assertEquals("§6 row 3 does not touch quality", 70, control.quality)

        // After the drop the interval is 125 ms, so 90 ms no longer trips the rule.
        control.onFeedback(feedback(decodeMsAvg = 90), rttMs = 0, nowMs = 1_100)
        assertEquals(8, control.targetFps)
    }

    @Test
    fun `frame rate never falls below one`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        // One report carrying both symptoms, second by second, for twenty seconds.
        var nowMs = 0L
        repeat(20) {
            nowMs += 1_000
            control.onFeedback(feedback(queueDepth = 9, decodeMsAvg = 5_000), rttMs = 0, nowMs = nowMs)
        }

        assertEquals("fps floor is 1", 1, control.targetFps)
        assertEquals("quality floor is 40", 40, control.quality)
    }

    @Test
    fun `high RTT costs quality at most once every two seconds`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        control.onFeedback(feedback(), rttMs = 150, nowMs = 0)
        assertEquals(62, control.quality)

        control.onFeedback(feedback(), rttMs = 150, nowMs = 1_000)
        assertEquals("2 s cooldown", 62, control.quality)

        control.onFeedback(feedback(), rttMs = 150, nowMs = 2_000)
        assertEquals(54, control.quality)
    }

    @Test
    fun `credits below a quarter of the window count only once they stay there`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10, initialCredits = 64L)

        control.onFeedback(feedback(credits = 10L), rttMs = 20, nowMs = 0)
        assertEquals("a single low report is not '长期'", 70, control.quality)

        control.onFeedback(feedback(credits = 10L), rttMs = 20, nowMs = 1_000)
        assertEquals(62, control.quality)
    }

    @Test
    fun `overloaded flag is a symptom on arrival`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        control.onFeedback(feedback(overloaded = true), rttMs = 0, nowMs = 0)
        assertEquals(62, control.quality)
    }

    @Test
    fun `quality bottoms out at forty before frame rate gives way`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        var nowMs = 0L
        repeat(6) {
            nowMs += 1_000
            control.onFeedback(feedback(queueDepth = 9), rttMs = 0, nowMs = nowMs)
        }

        assertEquals(40, control.quality)
        assertEquals("the sixth step has nowhere to go but cadence", 8, control.targetFps)
    }

    @Test
    fun `ten quiet seconds climb back four points at a time`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)
        control.onFeedback(feedback(overloaded = true), rttMs = 0, nowMs = 0)
        assertEquals(62, control.quality)

        control.onFeedback(feedback(), rttMs = 10, nowMs = 9_999)
        assertEquals("not ten seconds yet", 62, control.quality)

        control.onFeedback(feedback(), rttMs = 10, nowMs = 10_000)
        assertEquals(66, control.quality)

        control.onFeedback(feedback(), rttMs = 10, nowMs = 11_000)
        assertEquals("at most one climb per five seconds", 66, control.quality)

        control.onFeedback(feedback(), rttMs = 10, nowMs = 15_000)
        assertEquals(70, control.quality)

        control.onFeedback(feedback(), rttMs = 10, nowMs = 30_000)
        assertEquals("never above the negotiated value", 70, control.quality)
    }

    @Test
    fun `recovery also restores frame rate`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)
        control.onFeedback(feedback(decodeMsAvg = 90), rttMs = 0, nowMs = 0)
        assertEquals(8, control.targetFps)

        control.onFeedback(feedback(), rttMs = 10, nowMs = 10_000)
        assertEquals(9, control.targetFps)
    }

    @Test
    fun `an oversized frame cuts quality below the loop's floor decision`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        assertEquals(60, control.shrinkForOversize(nowMs = 0))
        assertEquals(50, control.shrinkForOversize(nowMs = 0))
        assertEquals(40, control.shrinkForOversize(nowMs = 0))
        assertNull("already at the floor; the frame cannot be made smaller within §6", control.shrinkForOversize(0))
        assertEquals(40, control.quality)
    }

    @Test
    fun `a value the watch pinned is not moved by the loop`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)
        assertEquals(80, control.setPinnedQuality(80))
        assertTrue(control.isQualityPinned)

        control.onFeedback(feedback(queueDepth = 9), rttMs = 0, nowMs = 0)
        control.onFeedback(feedback(queueDepth = 9), rttMs = 0, nowMs = 1_000)
        control.onFeedback(feedback(queueDepth = 9), rttMs = 0, nowMs = 2_000)

        assertEquals("the watch asked for 80; cadence is the loop's lever", 80, control.quality)
        assertEquals(6, control.targetFps)
    }

    @Test
    fun `SET_QUALITY with zero hands the decision back`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)
        control.setPinnedQuality(80)
        control.setPinnedQuality(0)
        assertFalse(control.isQualityPinned)

        control.onFeedback(feedback(queueDepth = 9), rttMs = 0, nowMs = 0)
        control.onFeedback(feedback(queueDepth = 9), rttMs = 0, nowMs = 1_000)

        assertEquals("the loop resumes from the value the pin left behind", 72, control.quality)
    }

    @Test
    fun `a pinned value is clamped into the range the protocol allows`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)

        assertEquals("quality ceiling", 85, control.setPinnedQuality(100))
        assertEquals("quality floor", 40, control.setPinnedQuality(10))
        assertEquals("frame rate ceiling is the negotiated one", 10, control.setPinnedFps(99))
    }

    @Test
    fun `a pinned frame rate survives a slow decoder`() {
        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)
        control.setPinnedFps(10)

        control.onFeedback(feedback(decodeMsAvg = 900), rttMs = 0, nowMs = 0)

        assertEquals(10, control.targetFps)
    }

    @Test
    fun `reset restores the negotiated values`() {        val control = RateControl(negotiatedQuality = 70, negotiatedFps = 10)
        control.onFeedback(feedback(queueDepth = 9), rttMs = 0, nowMs = 0)
        control.onFeedback(feedback(queueDepth = 9), rttMs = 0, nowMs = 1_000)
        control.onFrameSent()
        assertTrue("the loop moved off the negotiated value", control.quality < 70)

        control.reset()

        assertEquals(70, control.quality)
        assertEquals(10, control.targetFps)
        assertEquals("the startup window is back too", 56, control.effectiveQuality)
    }
}
