package com.watchplay.sdk.util

/**
 * Monotonic millisecond clock for all internal timeouts and statistics.
 *
 * Wall-clock time (`System.currentTimeMillis`) can jump when the user or the network changes the time,
 * which would make a timeout fire immediately or never. Capture timestamps, heartbeats, stale-frame
 * detection and RTT measurement all use this clock instead.
 *
 * `System.nanoTime()` is not wall-clock related but is monotonic, which is exactly what a timeout needs.
 */
object MonotonicClock {

    /** Current monotonic time in milliseconds. */
    fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** Current monotonic time in nanoseconds, for capture timestamps. */
    fun nowNs(): Long = System.nanoTime()
}
