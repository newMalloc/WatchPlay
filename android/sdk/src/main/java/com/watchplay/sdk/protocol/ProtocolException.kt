package com.watchplay.sdk.protocol

/**
 * Thrown when a byte sequence cannot be interpreted as a WatchPlay protocol structure.
 *
 * Callers should treat this as *recoverable at the transport level*: the specification never wants a
 * malformed frame to be partially parsed or to tear down a session. The correct reaction is to count
 * the error, resynchronise on the next `"WP"` magic, and keep going (spec §3, §7).
 */
class ProtocolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
