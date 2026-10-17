package com.watchplay.sdk.transport

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The scheduler behind retry-on-busy and reconnect backoff.
 *
 * `01-transport.md` §2.5 allows a transport to be closed and reopened; the retry path must therefore
 * work again after [Delay.shutdown] has been called once, otherwise a reopened link silently loses
 * every reconnect attempt.
 */
class ExecutorDelayTest {

    @Test
    fun `a scheduled action runs`() {
        val delay = ExecutorDelay()
        try {
            val latch = CountDownLatch(1)
            delay.after(0L) { latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
        } finally {
            delay.shutdown()
        }
    }

    @Test
    fun `the scheduler works again after a close`() {
        val delay = ExecutorDelay()
        delay.shutdown()

        val latch = CountDownLatch(1)
        try {
            delay.after(0L) { latch.countDown() }
            assertTrue("a reopened link lost its retry scheduler", latch.await(2, TimeUnit.SECONDS))
        } finally {
            delay.shutdown()
        }
    }
}
