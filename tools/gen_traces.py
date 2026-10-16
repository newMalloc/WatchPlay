"""Generate canonical WatchPlay v0.1 protocol traces.

Everything quoted in docs/spec/ is produced here, so the spec and the code
cannot drift apart. Run:

    python tools/gen_traces.py

Writes examples/protocol-traces/*.txt/.bin and appends/refreshes the
`AUTOGEN` block in examples/protocol-traces/README.md.

Self-check mode (used by CI):

    python tools/gen_traces.py --check
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from watchplay_proto import (  # noqa: E402
    DEFAULT_MAX_CHUNK_BYTES,
    VIDEO_CODEC_MJPEG,
    VIDEO_SUBHEADER_SIZE,
    Flags,
    FrameType,
    Header,
    crc16_ccitt_false,
    encode,
    parse_header,
    StreamParser,
)

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "examples" / "protocol-traces"

# The worked example in docs/spec/02-wire-format.md section 8.
# Verified against the reference hardware: 466x466 round AMOLED.
EXAMPLE_JPEG_SIZE = 8123
EXAMPLE_CHUNK_BYTES = DEFAULT_MAX_CHUNK_BYTES
EXAMPLE_SEQ = 42
EXAMPLE_FRAME_ID = 7


def build_chunk(
    *,
    seq: int,
    frame_id: int,
    chunk_index: int,
    chunk_total: int,
    codec: int,
    width: int,
    height: int,
    quality: int,
    capture_ts_ms: int,
    encoded_frame_bytes: int,
    chunk_data: bytes,
    first: bool,
    last: bool,
    keyframe: bool,
) -> bytes:
    """Build one complete VIDEO protocol frame.

    Layout of the 20-byte video sub-header at the start of the payload
    (little-endian, packed) — normative, see docs/spec/04-video.md section 2:

        off  len  field
        0    4    frame_id            frame counter, sender-scoped
        4    2    chunk_index         0-based index of this chunk
        6    2    chunk_total         number of chunks in this frame
        8    1    codec               1=MJPEG, 2=H264
        9    1    vflags              video-level flags (bit0 keyframe, ...)
        10   2    width               coded width in pixels
        12   2    height              coded height in pixels
        14   1    quality             JPEG quality 1..100 (0 when codec=H264)
        15   1    reserved            MUST be 0, receivers ignore
        16   4    frame_bytes         total encoded size of the whole frame
        20   ..   chunk_data          up to (max_chunk_bytes - 20) bytes

    `capture_ts_ms` is NOT carried per chunk: it belongs to the frame, and
    repeating a 40-bit millisecond timestamp in all 34 chunks of a frame is
    ~6% pure overhead on a 244-byte payload budget. It is carried once per
    frame in the FIRST_CHUNK's `chunk_data` prefix instead (JPEG: after SOI;
    H.264: in the SEI). See docs/spec/04-video.md section 2.3.
    """
    vh = bytearray()
    vh += frame_id.to_bytes(4, "little")             # 0..3
    vh += chunk_index.to_bytes(2, "little")          # 4..5
    vh += chunk_total.to_bytes(2, "little")          # 6..7
    vh += bytes((codec,))                            # 8
    vh += bytes((1 if keyframe else 0,))             # 9  vflags
    vh += width.to_bytes(2, "little")                # 10..11
    vh += height.to_bytes(2, "little")               # 12..13
    vh += bytes((quality if codec == VIDEO_CODEC_MJPEG else 0,))  # 14
    vh += bytes((0,))                                # 15 reserved
    vh += encoded_frame_bytes.to_bytes(4, "little")  # 16..19
    assert len(vh) == VIDEO_SUBHEADER_SIZE, len(vh)
    del capture_ts_ms  # see docstring

    payload = bytes(vh) + chunk_data
    flags = 0
    if keyframe:
        flags |= Flags.KEYFRAME
    if first:
        flags |= Flags.FIRST_CHUNK
    if last:
        flags |= Flags.LAST_CHUNK
    return encode(FrameType.VIDEO, seq, payload, flags)


def build_example_chunks() -> list[bytes]:
    """The exact MJPEG chunk sequence described in spec 02 section 8.

    Chunking rule (spec 04 section 3): `payload_len <= max_chunk_bytes`, and the
    payload *includes* the 20-byte video sub-header. So the number of payload
    bytes available for JPEG data per chunk is `max_chunk_bytes - 20`.
    """
    total_bytes = EXAMPLE_JPEG_SIZE
    max_payload = EXAMPLE_CHUNK_BYTES
    per_chunk = max_payload - VIDEO_SUBHEADER_SIZE  # 224
    chunk_total = -(-total_bytes // per_chunk)  # ceil -> 37
    chunks = []
    offset = 0
    for i in range(chunk_total):
        n = min(per_chunk, total_bytes - offset)
        # deterministic pseudo-JPEG body so the trace is reproducible
        data = bytes(((offset + j) * 31 + 17) & 0xFF for j in range(n))
        chunks.append(
            build_chunk(
                seq=EXAMPLE_SEQ,
                frame_id=EXAMPLE_FRAME_ID,
                chunk_index=i,
                chunk_total=chunk_total,
                codec=VIDEO_CODEC_MJPEG,
                width=466,
                height=466,
                quality=72,
                capture_ts_ms=1_700_000_000_000 + i,
                encoded_frame_bytes=total_bytes,
                chunk_data=data,
                first=(i == 0),
                last=(i == chunk_total - 1),
                keyframe=True,
            )
        )
        offset += n
    return chunks


def hexdump(data: bytes, prefix: str = "  ", width: int = 16) -> str:
    lines = []
    for off in range(0, len(data), width):
        row = data[off : off + width]
        hexpart = " ".join(f"{b:02X}" for b in row)
        asciipart = "".join(chr(b) if 32 <= b < 127 else "." for b in row)
        lines.append(f"{prefix}{off:08X}  {hexpart:<{width * 3}}  {asciipart}")
    return "\n".join(lines)


def annotated_first_chunk(data: bytes) -> str:
    """Human-readable field-by-field breakdown of a VIDEO frame's headers."""
    h = parse_header(data)
    pl = data[h.hdr_len :]
    rows = [
        ("57 50", "magic", '"WP"'),
        (f"{data[2]:02X}", "version", str(data[2])),
        (f"{data[3]:02X}", "type", f"{data[3]} (VIDEO)"),
        (f"{data[4]:02X} {data[5]:02X}", "flags", f"0x{h.flags:04X}"),
        (f"{data[6]:02X} {data[7]:02X}", "hdr_len", str(h.hdr_len)),
        (" ".join(f"{b:02X}" for b in data[8:12]), "seq", str(h.seq)),
        (f"{data[12]:02X} {data[13]:02X}", "payload_len", str(h.payload_len)),
        (f"{data[14]:02X} {data[15]:02X}", "hdr_crc", f"0x{int.from_bytes(data[14:16], 'little'):04X}"),
        ("—", "*video sub-header begins*", ""),
        (" ".join(f"{b:02X}" for b in pl[0:4]), "frame_id", str(int.from_bytes(pl[0:4], "little"))),
        (" ".join(f"{b:02X}" for b in pl[4:6]), "chunk_index", str(int.from_bytes(pl[4:6], "little"))),
        (" ".join(f"{b:02X}" for b in pl[6:8]), "chunk_total", str(int.from_bytes(pl[6:8], "little"))),
        (f"{pl[8]:02X}", "codec", f"{pl[8]} (MJPEG)"),
        (f"{pl[9]:02X}", "vflags", f"0x{pl[9]:02X}"),
        (" ".join(f"{b:02X}" for b in pl[10:12]), "width", str(int.from_bytes(pl[10:12], "little"))),
        (" ".join(f"{b:02X}" for b in pl[12:14]), "height", str(int.from_bytes(pl[12:14], "little"))),
        (f"{pl[14]:02X}", "quality", str(pl[14])),
        (f"{pl[15]:02X}", "reserved", "0"),
        (" ".join(f"{b:02X}" for b in pl[16:20]), "frame_bytes", str(int.from_bytes(pl[16:20], "little"))),
    ]
    width = max(len(r[0]) for r in rows)
    out = ["| bytes | field | value |", "| :--- | :--- | :--- |"]
    for hexs, name, val in rows:
        out.append(f"| `{hexs}` | `{name.lstrip()}` | {val} |")
    del width
    return "\n".join(out)


def build_session_trace() -> bytes:
    """A complete minimal session: HELLO -> HELLO_ACK -> CAPS -> CAPS_ACK -> PING/PONG."""
    parts = []
    parts.append(encode(FrameType.HELLO, 1, b"WPHELLO\x01" + b"tdisplay-s3-amoled"))
    parts.append(encode(FrameType.HELLO_ACK, 1, b"WPHELLO_ACK\x01" + b"android-sdk-0.1"))
    parts.append(encode(FrameType.CAPS, 2, bytes(range(0, 48))))
    parts.append(encode(FrameType.CAPS_ACK, 2, bytes(range(0, 24))))
    parts.append(encode(FrameType.PING, 3, b"\x00\x00\x00\x00"))
    parts.append(encode(FrameType.PONG, 3, b"\x00\x00\x00\x00"))
    return b"".join(parts)


def check_spec_quotes(chunks: list[bytes]) -> list[str]:
    """Verify the hex bytes quoted in the spec actually match the trace.

    `docs/spec/02-wire-format.md` §8 quotes the first 32 bytes and the last
    chunk header verbatim. Quoted bytes rot silently when the generator changes,
    and a spec that shows the wrong bytes is worse than one that shows none.
    Returns a list of human-readable problems (empty when clean).
    """
    problems: list[str] = []
    spec = REPO / "docs" / "spec" / "02-wire-format.md"
    if not spec.exists():
        return [f"{spec} missing"]

    text = spec.read_text(encoding="utf-8")
    lines = [ln for ln in text.splitlines() if ln.startswith("00000000") or ln.startswith("57 50 01 05 28")]
    if len(lines) < 2:
        return [f"{spec}: expected 2 quoted byte lines in section 8, found {len(lines)}"]

    # Line 1 is a hexdump with a "00000000  " prefix and a trailing ASCII column.
    dump = lines[0].split("  ", 1)[1] if "  " in lines[0] else lines[0]
    dump = dump.split("<")[0]
    quoted_first = [t for t in dump.split() if len(t) == 2 and all(c in "0123456789ABCDEF" for c in t)]
    actual_first = [f"{b:02X}" for b in chunks[0][: len(quoted_first)]]
    if quoted_first != actual_first:
        problems.append(
            "spec 02 section 8 first-chunk bytes disagree with the trace:\n"
            f"    quoted: {' '.join(quoted_first)}\n"
            f"    actual: {' '.join(actual_first)}"
        )

    quoted_last = [t for t in lines[1].split() if len(t) == 2 and all(c in "0123456789ABCDEF" for c in t)]
    actual_last = [f"{b:02X}" for b in chunks[-1][: len(quoted_last)]]
    if quoted_last != actual_last:
        problems.append(
            "spec 02 section 8 last-chunk header disagrees with the trace:\n"
            f"    quoted: {' '.join(quoted_last)}\n"
            f"    actual: {' '.join(actual_last)}"
        )
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="verify committed traces match generator")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)

    chunks = build_example_chunks()
    assert len(chunks) == 37, f"expected 37 chunks (spec 02 section 8), got {len(chunks)}"

    blob = b"".join(chunks)
    session = build_session_trace()

    artifacts: dict[str, bytes] = {
        "mjpeg-frame-466x466-3c.bin": blob,
        "session-handshake.bin": session,
    }

    if args.check:
        ok = True
        for name, data in artifacts.items():
            p = OUT / name
            if not p.exists():
                print(f"MISSING {p}")
                ok = False
            elif p.read_bytes() != data:
                print(f"STALE   {p}")
                ok = False
        # round-trip parse
        sp = StreamParser()
        frames = sp.feed(blob)
        expect = {"frames": 37, "hdr_crc_errors": 0, "resync_count": 0}
        for k, v in expect.items():
            if sp.stats()[k] != v:
                print(f"PARSE FAIL {k}={sp.stats()[k]} want {v}")
                ok = False
        if len(frames) != 37:
            print(f"PARSE FAIL frame count {len(frames)}")
            ok = False
        for problem in check_spec_quotes(chunks):
            print(f"SPEC QUOTE FAIL {problem}")
            ok = False
        print("check: OK" if ok else "check: FAILED")
        return 0 if ok else 1

    for name, data in artifacts.items():
        (OUT / name).write_bytes(data)

    first = chunks[0]
    last = chunks[-1]
    doc = []
    doc.append("# Protocol traces (generated)\n")
    doc.append(
        "> **AUTOGEN** — regenerate with `python tools/gen_traces.py`.\n"
        "> These files are the ground truth for parser unit tests; do not hand-edit.\n"
    )
    doc.append("## `mjpeg-frame-466x466-3c.bin`\n")
    doc.append(
        f"One complete MJPEG frame as it appears on the wire: {EXAMPLE_JPEG_SIZE} bytes of\n"
        f"JPEG split into {len(chunks)} VIDEO frames of at most {EXAMPLE_CHUNK_BYTES} bytes payload.\n"
        f"`seq = {EXAMPLE_SEQ}`, `frame_id = {EXAMPLE_FRAME_ID}`, 466x466, quality 72.\n"
    )
    doc.append(f"Total on-wire size: **{len(blob)} bytes**.\n")
    doc.append(f"sha256: `{hashlib.sha256(blob).hexdigest()}`\n")
    doc.append("### First chunk, byte by byte\n")
    doc.append("```")
    doc.append(hexdump(first[:64]))
    doc.append("```\n")
    doc.append(annotated_first_chunk(first) + "\n")
    doc.append("### Last chunk header\n")
    doc.append("```")
    doc.append(hexdump(last[:32]))
    doc.append("```\n")
    doc.append(
        f"Note `flags` is now `0x{int.from_bytes(last[4:6], 'little'):04X}` "
        "(`LAST_CHUNK` set, `FIRST_CHUNK` clear) and `payload_len` is "
        f"{int.from_bytes(last[12:14], 'little')} — the remainder of the frame.\n"
    )
    doc.append("## `session-handshake.bin`\n")
    doc.append(
        "A minimal happy-path session: `HELLO`, `HELLO_ACK`, `CAPS`, `CAPS_ACK`,\n"
        "`PING`, `PONG`. Payloads here are illustrative placeholders — the real\n"
        "structures are defined in [03-session.md](../../docs/spec/03-session.md) and\n"
        "[07-capabilities.md](../../docs/spec/07-capabilities.md).\n"
    )
    doc.append(f"Total: **{len(session)} bytes**.\n")
    doc.append(f"sha256: `{hashlib.sha256(session).hexdigest()}`\n")
    doc.append("```")
    doc.append(hexdump(session[:96]))
    doc.append("```\n")
    doc.append("## Conformance\n")
    doc.append(
        "A conforming parser must reassemble all 37 chunks into exactly one frame of\n"
        f"{EXAMPLE_JPEG_SIZE} bytes with **zero** CRC errors and **zero** resyncs:\n\n"
        "```python\n"
        "from tools.watchplay_proto import StreamParser\n"
        "parser = StreamParser()\n"
        "frames = parser.feed(open('examples/protocol-traces/mjpeg-frame-466x466-3c.bin','rb').read())\n"
        "assert len(frames) == 37\n"
        "assert parser.stats()['hdr_crc_errors'] == 0\n"
        "```\n"
    )
    doc.append("## CRC-16 spot checks\n")
    doc.append("| input | expected `hdr_crc` for a zeroed header |")
    doc.append("| :--- | :--- |")
    for s in (b"123456789", b"WP", b""):
        doc.append(f"| `{s.decode('latin1')!r}` | `0x{crc16_ccitt_false(s):04X}` |")
    doc.append("")
    doc.append(
        "`crc16_ccitt_false(b\"123456789\") == 0x29B1` is the standard check value and is\n"
        "asserted in `tools/test_protocol.py`.\n"
    )

    (OUT / "README.md").write_text("\n".join(doc), encoding="utf-8")

    print(f"wrote {len(artifacts)} binaries + README.md to {OUT}")
    print(f"first chunk ({len(first)} bytes): {first[:32].hex(' ').upper()}")
    print(f"last chunk payload_len={int.from_bytes(last[12:14],'little')}, flags=0x{int.from_bytes(last[4:6],'little'):04X}")
    print(f"mjpeg trace sha256={hashlib.sha256(blob).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
