package com.watchplay.sdk.protocol

/**
 * Protocol version this SDK speaks.
 *
 * `docs/spec/03-session.md` §2.5: v0.1 implementations support version `1` only. A frame whose header
 * `version` this SDK cannot parse must be rejected with `BYE(UNSUPPORTED_VERSION)` — never partially
 * parsed (spec `02-wire-format.md` §7 step 4).
 */
object ProtocolVersion {
    /** Numeric wire version: byte 2 of every frame header, and `HELLO.proto_version`. */
    const val CURRENT: Int = 1

    /** Lowest version this SDK can also interpret (none: only 1 exists). */
    const val MIN_SUPPORTED: Int = 1

    /** Human-readable draft label used by `docs/spec/`. */
    const val DRAFT_LABEL: String = "v0.1"

    /** `true` when [version] can be parsed by this SDK. */
    fun isSupported(version: Int): Boolean = version in MIN_SUPPORTED..CURRENT
}

/**
 * Every numeric limit and default the specification fixes, in one place.
 *
 * Sources: `02-wire-format.md` §1/§6, `03-session.md` §2/§3/§4/§6, `04-video.md` §3/§4/§6 and
 * `01-transport.md` §5/§6. Keeping them here means the encoder, the chunker, the parser and the
 * negotiator can never disagree about a size.
 */
object ProtocolLimits {

    /** Fixed frame header size; `hdr_len` is currently always this value. */
    const val HEADER_SIZE: Int = 16

    /** Smallest acceptable `hdr_len`; below this a frame is malformed (`02` §1.1). */
    const val MIN_HEADER_SIZE: Int = 16

    /** Largest acceptable `hdr_len`; above this the parser resyncs (`02` §7). */
    const val MAX_HEADER_SIZE: Int = 256

    /** `payload_len` is a u16, so this is a hard protocol ceiling (`02` §1). */
    const val MAX_PAYLOAD_BYTES: Int = 0xFFFF

    /**
     * Default `max_chunk_bytes` — the `payload_len` ceiling of one `VIDEO` chunk, including its
     * 20-byte video sub-header. Matches the recommended ATT MTU 247 (`01` §2.2, `04` §3.1).
     */
    const val DEFAULT_MAX_CHUNK_BYTES: Int = 244

    /**
     * Smallest usable `max_chunk_bytes`: `20` sub-header + a 64-byte minimum data block
     * (`03-session.md` §4.2 step 5). The negotiator **MUST** reject anything smaller.
     */
    const val MIN_MAX_CHUNK_BYTES: Int = 84

    /** Fixed size of the per-chunk video sub-header (`04-video.md` §2). */
    const val VIDEO_SUBHEADER_SIZE: Int = 20

    /** Largest single media payload a chunk can carry, at the default chunk budget. */
    const val DEFAULT_MAX_MEDIA_BYTES_PER_CHUNK: Int = DEFAULT_MAX_CHUNK_BYTES - VIDEO_SUBHEADER_SIZE

    /**
     * Recommended ATT MTU (`01-transport.md` §2.2). 247 yields a 244-byte usable payload, exactly
     * [DEFAULT_MAX_CHUNK_BYTES]. 517 is explicitly discouraged: some stacks report 517 but truncate
     * notifications at 512, producing a 2-byte-short frame and a header-CRC resync storm.
     */
    const val DEFAULT_MTU: Int = 247

    /** Smallest ATT MTU allowed by the Bluetooth specification. */
    const val MIN_ATT_MTU: Int = 23

    /** ATT write overhead: 1 byte opcode + 2 bytes attribute handle (`01` §2.2). */
    const val ATT_WRITE_OVERHEAD: Int = 3

    /** `HELLO` is a fixed 52-byte payload (`03-session.md` §2.1). */
    const val HELLO_PAYLOAD_SIZE: Int = 52

    /** `HELLO_ACK` is a fixed 24-byte payload (`03-session.md` §2.2). */
    const val HELLO_ACK_PAYLOAD_SIZE: Int = 24

    /** `CAPS` / `CAPS_ACK` total payload budget (`07-capabilities.md` §5). */
    const val CAPS_MAX_PAYLOAD: Int = 128

    /** `CAPS` TLV sizes (`03-session.md` §3.2–§3.8). */
    const val TLV_DEVICE_INFO_SIZE: Int = 16
    const val TLV_DISPLAY_SIZE: Int = 16
    const val TLV_CODEC_MJPEG_SIZE: Int = 4
    const val TLV_CODEC_H264_SIZE: Int = 8
    const val TLV_INPUT_SIZE: Int = 4
    const val TLV_AUDIO_SIZE: Int = 4
    const val TLV_MEMORY_SIZE: Int = 8

    /** `CAPS_ACK` TLV sizes (`03-session.md` §4, §4.1). */
    const val TLV_RESULT_SIZE: Int = 24
    const val TLV_STREAM_SIZE: Int = 12

    /** `TOUCH` is a fixed 14-byte payload (`05-input.md` §1). */
    const val TOUCH_PAYLOAD_SIZE: Int = 14

    /** `FEEDBACK` is a fixed 20-byte payload (`04-video.md` §4.2). */
    const val FEEDBACK_PAYLOAD_SIZE: Int = 20

    /** `PING` / `PONG` payload size (`03-session.md` §6.1). */
    const val PING_PAYLOAD_SIZE: Int = 8

    /** `CONTROL` payload cap (`02` §6). */
    const val CONTROL_MAX_PAYLOAD: Int = 64

    /** Initial credit window when the receiver has not told us otherwise (`03` §4.2 step 7). */
    const val DEFAULT_INITIAL_CREDITS: Int = 64

    /** Negotiated `initial_credits` bounds (`03-session.md` §4.2 step 7). */
    const val MIN_INITIAL_CREDITS: Int = 16
    const val MAX_INITIAL_CREDITS: Int = 128

    /** JPEG quality is clamped to this window (`04-video.md` §6, hard constraint). */
    const val MIN_QUALITY: Int = 40
    const val MAX_QUALITY: Int = 85

    /** Negotiated frame-rate bounds (`03-session.md` §4.2 step 3). */
    const val MIN_FPS: Int = 1
    const val MAX_FPS: Int = 30

    /** H.264 GOP bounds: about 0.8 s, clamped (`03-session.md` §4.2 step 6, `04` §1.1). */
    const val MIN_GOP: Int = 8
    const val MAX_GOP: Int = 12

    /**
     * Frame-header reassembly buffer ceiling: `2 x (16 + max_chunk_bytes)` = 520 bytes by default
     * (`01-transport.md` §5.4). Exceeding it means the stream has lost framing, so the parser drops
     * its buffer and counts a resync rather than growing without bound.
     */
    const val DEFAULT_PARSER_BUFFER_BYTES: Int = 2 * (HEADER_SIZE + DEFAULT_MAX_CHUNK_BYTES)

    /**
     * Default video-frame reassembly ceiling, used only when the peer has not sent
     * `CAPS_ACK.STREAM.max_frame_bytes`. 1 MiB is comfortably above a 466×466 JPEG at quality 85.
     */
    const val DEFAULT_MAX_FRAME_BYTES: Int = 1024 * 1024

    /** `03-session.md` §6.2 heartbeat defaults. */
    const val DEFAULT_HEARTBEAT_MS: Int = 2_000
    const val DEFAULT_PONG_TIMEOUT_MS: Int = 1_000
    const val DEFAULT_MISSING_PONGS_BEFORE_BYE: Int = 3
    const val DEFAULT_KEEPALIVE_GRACE_MS: Int = 6_000

    /** `03-session.md` §1.1 state timeouts. */
    const val CONNECTING_TIMEOUT_MS: Int = 10_000
    const val HANDSHAKING_TIMEOUT_MS: Int = 3_000
    const val NEGOTIATING_TIMEOUT_MS: Int = 3_000
    const val DRAIN_TIMEOUT_MS: Int = 2_000

    /** `05-input.md` §3.1 phone-side stuck-touch recovery. */
    const val STUCK_TOUCH_TIMEOUT_MS: Long = 10_000L
}
