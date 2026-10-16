package com.watchplay.sdk.session

/**
 * Session lifecycle of the phone side (`03-session.md` §1).
 *
 * ```
 *   IDLE --open()--> CONNECTING --link up--> HANDSHAKING --HELLO--> NEGOTIATING --CAPS_ACK--> STREAMING
 *                        |                       |                      |                      |
 *                        +-----------------------+----------------------+--> FAILED <--BYE/超时
 *                                                                              ^
 *                                                     STREAMING --credit=0--> DRAINING --RESUME--+
 * ```
 *
 * Two things here are easy to get wrong:
 *
 *  * **The watch initiates.** `HELLO` and `CAPS` are Sink → Source frames (`03-session.md` §1.2), so the
 *    phone waits after the link comes up; there is no `HELLO_SENT` state.
 *  * **`DRAINING` is backpressure, not shutdown.** It means "stop sending `VIDEO`, keep heartbeating and
 *    exchanging `FEEDBACK`, and return to `STREAMING` when credit comes back"
 *    (`03-session.md` §1.1 note, `04-video.md` §5.4). A session that treats it as teardown will drop the
 *    link every time the watch's decoder queue fills.
 *
 * The terminal state is [FAILED], matching the specification; there is deliberately no extra `CLOSED`
 * state, because `01-transport.md` §8.1 forbids a bearer or session layer from inventing states outside
 * this machine.
 */
enum class SessionState {

    /** Nothing started. */
    IDLE,

    /** The bearer is connecting (10 s budget, `03-session.md` §1.1). */
    CONNECTING,

    /** Link up; waiting for the watch's `HELLO` (3 s budget). */
    HANDSHAKING,

    /** `HELLO_ACK` sent; waiting for `CAPS` (3 s budget). */
    NEGOTIATING,

    /** `CAPS_ACK` sent: video, touch and feedback may flow. */
    STREAMING,

    /** Backpressure: no `VIDEO`, but heartbeats and `FEEDBACK` continue; returns to [STREAMING]. */
    DRAINING,

    /** Session error or teardown; only `BYE` may be sent, and a reconnect does a full handshake. */
    FAILED;

    /** `true` while a full handshake is in progress. */
    val isHandshaking: Boolean
        get() = this == CONNECTING || this == HANDSHAKING || this == NEGOTIATING

    /** `true` when `VIDEO` frames may be sent. */
    val canStreamVideo: Boolean
        get() = this == STREAMING

    /** `true` when nothing further will happen without reopening. */
    val isTerminal: Boolean
        get() = this == FAILED
}
