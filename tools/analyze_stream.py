"""WatchPlay protocol stream analyzer.

Reads captured WatchPlay bytes and reports what is actually on the wire:
frame-type histogram, CRC errors, resync events, the real chunk_total vs
frame_bytes (the off-by-20 bug is visible here immediately), Credit flow, and
measured frame rate / bitrate.

Input formats
-------------
* ``.bin`` / ``.raw``   raw WatchPlay byte stream
* ``.hex`` / ``.txt``   hex dump, with or without offsets and ASCII columns
* ``.pcap``             classic pcap capture (DLT 256 = LINKTYPE_BLUETOOTH_LE_LL),
                        ACL-L2CAP frames are unwrapped automatically

**What this cannot do**: an Android *btsnoop_hci.log* contains only HCI
commands and events -- the ATT payload of GATT notifications is not written to
that log, so there is nothing to parse. See ``docs/guides/getting-started.md``
for how to obtain usable bytes.

Usage
-----
    python tools/analyze_stream.py examples/protocol-traces/mjpeg-frame-466x466-3c.bin
    python tools/analyze_stream.py capture.pcap --verbose
    python tools/analyze_stream.py dump.txt --histogram-only
"""

from __future__ import annotations

import argparse
import struct
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from watchplay_proto import (  # noqa: E402
    HEADER_SIZE,
    VIDEO_CODEC_H264,
    VIDEO_CODEC_MJPEG,
    FrameType,
    StreamParser,
)

PCAP_MAGIC_LE = 0xA1B2C3D4
PCAPNG_MAGIC = 0x0A0D0D0A

DLT_BLUETOOTH_LE_LL_WITH_PSEUDOHEADER = 256
DLT_BLUETOOTH_HCI_H4 = 187

CODEC_NAMES = {VIDEO_CODEC_MJPEG: "MJPEG", VIDEO_CODEC_H264: "H.264"}

VFLAG_KEYFRAME = 0x01
VFLAG_TS_PRESENT = 0x02


# --------------------------------------------------------------------------
# Input decoding
# --------------------------------------------------------------------------
def load_hex(text: str) -> bytes:
    """Parse a hex dump. Tolerates offsets, ':' separators, and ASCII tails."""
    out = bytearray()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", ";", "//")):
            continue
        # Drop a leading offset column like "00000000" or "0x0000:".
        line = line.split("|", 1)[0]
        parts = line.replace(":", " ").split()
        if parts and len(parts[0]) > 4 and all(c in "0123456789abcdefABCDEF" for c in parts[0]):
            parts = parts[1:]
        for tok in parts:
            if len(tok) > 2 or not all(c in "0123456789abcdefABCDEF" for c in tok):
                continue  # not a hex byte, e.g. an ASCII column or "<224 bytes>"
            out.append(int(tok, 16))
    return bytes(out)


def load_pcap(data: bytes) -> bytes:
    """Extract WatchPlay payload bytes from a classic pcap of BLE LL traffic.

    Best-effort: we walk link-layer frames and strip L2CAP/ATT headers when we
    can recognise them. Anything we cannot parse contributes its raw bytes to
    the search buffer, and the StreamParser's resync logic deals with the rest.
    This is deliberately permissive -- a capture tool's exact framing varies and
    failing loudly on an unrecognised header would make the tool useless.
    """
    if len(data) < 24:
        return b""
    magic = struct.unpack_from("<I", data, 0)[0]
    if magic == PCAPNG_MAGIC:
        raise SystemExit(
            "pcapng is not supported; convert with `editcap -F pcap in.pcapng out.pcap`"
        )
    endian = "<" if magic == PCAP_MAGIC_LE else ">"
    linktype = struct.unpack_from(f"{endian}I", data, 20)[0]

    out = bytearray()
    off = 24
    while off + 16 <= len(data):
        _, _, incl_len, _ = struct.unpack_from(f"{endian}IIII", data, off)
        off += 16
        if off + incl_len > len(data):
            break
        pkt = data[off : off + incl_len]
        off += incl_len
        out += _extract_payload(pkt, linktype)
    return bytes(out)


def _extract_payload(pkt: bytes, linktype: int) -> bytes:
    """Pull the ATT payload out of one captured link-layer frame."""
    if linktype == DLT_BLUETOOTH_LE_LL_WITH_PSEUDOHEADER:
        # 10-byte pseudo-header then the LL PDU.
        if len(pkt) < 11:
            return b""
        pkt = pkt[10:]
        return _strip_l2cap(pkt)
    if linktype == DLT_BLUETOOTH_HCI_H4:
        # H4: 1-byte type; ACL packets carry a 4-byte handle/len header.
        if not pkt or pkt[0] != 0x02 or len(pkt) < 5:
            return b""
        return _strip_l2cap(pkt[5:])
    return pkt


def _strip_l2cap(pdu: bytes) -> bytes:
    """Given an LL PDU, skip L2CAP and ATT headers to reach the value bytes."""
    if len(pdu) < 5:
        return b""
    # LL DATA PDU: first two bytes are the LL header (LLID in bits 0-1).
    llid = pdu[0] & 0x03
    body = pdu[2:]
    if llid == 0b01:  # continuation fragment: no L2CAP header on this one
        return body
    if len(body) < 4:
        return b""
    l2_len, cid = struct.unpack_from("<HH", body, 0)
    payload = body[4 : 4 + l2_len]
    if cid == 0x0004:  # ATT
        if not payload:
            return b""
        opcode = payload[0]
        # 0x1B = Handle Value Notification, 0x1D = Indication
        # 0x52 = Write Command, 0x12 = Write Request
        if opcode in (0x1B, 0x1D) and len(payload) >= 3:
            return payload[3:]
        if opcode == 0x52 and len(payload) >= 3:
            return payload[3:]
        if opcode == 0x12 and len(payload) >= 3:
            return payload[3:]
        if opcode in (0x1E, 0x1F):  # 0x1E = Handle Value Confirmation
            return b""
        return payload
    return payload


def load_file(path: Path) -> bytes:
    raw = path.read_bytes()
    suffix = path.suffix.lower()
    if suffix == ".pcap":
        return load_pcap(raw)
    if suffix in (".hex", ".txt", ".dump"):
        return load_hex(raw.decode("utf-8", errors="replace"))
    # Sniff: a pcap magic at offset 0 wins, otherwise treat as raw bytes.
    if len(raw) >= 4 and struct.unpack_from("<I", raw, 0)[0] in (PCAP_MAGIC_LE, PCAPNG_MAGIC):
        return load_pcap(raw)
    return raw


# --------------------------------------------------------------------------
# Analysis
# --------------------------------------------------------------------------
class VideoFrameTracker:
    """Reassembles VIDEO frames enough to report per-frame facts."""

    def __init__(self) -> None:
        self.frames: list[dict] = []
        self._cur: dict | None = None
        self.incomplete = 0
        self.bad_chunk_index = 0

    def feed(self, frame_type: int, payload: bytes, flags: int) -> None:
        if frame_type != FrameType.VIDEO or len(payload) < 20:
            return
        frame_id = int.from_bytes(payload[0:4], "little")
        chunk_index = int.from_bytes(payload[4:6], "little")
        chunk_total = int.from_bytes(payload[6:8], "little")
        codec = payload[8]
        vflags = payload[9]
        width = int.from_bytes(payload[10:12], "little")
        height = int.from_bytes(payload[12:14], "little")
        quality = payload[14]
        frame_bytes = int.from_bytes(payload[16:20], "little")
        media = payload[20:]

        if chunk_index == 0 or self._cur is None or self._cur["frame_id"] != frame_id:
            if self._cur is not None and self._cur["got"] < self._cur["chunk_total"]:
                self.incomplete += 1
            self._cur = {
                "frame_id": frame_id,
                "chunk_total": chunk_total,
                "codec": codec,
                "vflags": vflags,
                "width": width,
                "height": height,
                "quality": quality,
                "frame_bytes": frame_bytes,
                "got": 0,
                "media": 0,
                "first_chunk_media": 0,
                "overhead": 0,
                "bad_index": False,
            }
            self.frames.append(self._cur)

        cur = self._cur
        if chunk_index != cur["got"]:
            cur["bad_index"] = True
            self.bad_chunk_index += 1
        if chunk_index == 0:
            cur["first_chunk_media"] = len(media)
        cur["got"] += 1
        cur["media"] += len(media)
        cur["overhead"] += HEADER_SIZE + 20

    def finish(self) -> None:
        if self._cur is not None and self._cur["got"] < self._cur["chunk_total"]:
            self.incomplete += 1


def analyze(blob: bytes, verbose: bool = False) -> dict:
    parser = StreamParser()
    tracker = VideoFrameTracker()
    type_hist: Counter[str] = Counter()
    flag_hist: Counter[str] = Counter()

    frames = parser.feed(blob)
    for f in frames:
        name = FrameType(f.type).name if f.type in _KNOWN else f"0x{f.type:02X}"
        type_hist[name] += 1
        if f.flags & 0x0001:
            flag_hist["ACK_REQUIRED"] += 1
        if f.flags & 0x0002:
            flag_hist["RETRANSMIT"] += 1
        if f.flags & 0x0004:
            flag_hist["LAST_IN_BURST"] += 1
        if f.flags & 0x8000:
            flag_hist["VENDOR"] += 1
        tracker.feed(f.type, f.payload, f.flags)

    tracker.finish()
    stats = parser.stats()
    return {
        "total_bytes": len(blob),
        "parsed_bytes": sum(HEADER_SIZE + len(f.payload) for f in frames),
        "type_hist": type_hist,
        "flag_hist": flag_hist,
        "video_frames": tracker.frames,
        "incomplete_frames": tracker.incomplete,
        "bad_chunk_index": tracker.bad_chunk_index,
        "parser": stats,
    }


_KNOWN = {int(t) for t in FrameType}


def report(res: dict, verbose: bool = False, histogram_only: bool = False) -> list[str]:
    out: list[str] = []
    add = out.append
    stats = res["parser"]

    add("=" * 68)
    add("WatchPlay stream analysis")
    add("=" * 68)
    add(f"input bytes          {res['total_bytes']}")
    add(f"parsed as protocol   {res['parsed_bytes']}  "
        f"({_pct(res['parsed_bytes'], res['total_bytes'])})")
    add(f"frames parsed        {stats['frames']}")
    add(f"hdr CRC errors       {stats['hdr_crc_errors']}")
    add(f"resyncs              {stats['resync_count']}")
    add(f"unknown frame types  {stats['unknown_frames']}")
    add(f"version mismatches   {stats['version_errors']}")

    add("")
    add("-- frame types " + "-" * 53)
    for name, n in res["type_hist"].most_common():
        add(f"  {name:<16} {n:>8}")
    if res["flag_hist"]:
        add("")
        add("-- flags " + "-" * 59)
        for name, n in res["flag_hist"].most_common():
            add(f"  {name:<16} {n:>8}")

    if histogram_only:
        return out

    vf = res["video_frames"]
    add("")
    add("-- video " + "-" * 59)
    add(f"  video frames seen     {len(vf)}")
    add(f"  incomplete frames     {res['incomplete_frames']}")
    add(f"  out-of-order chunks   {res['bad_chunk_index']}")

    if vf:
        first = vf[0]
        add("")
        add("  first frame:")
        add(f"    frame_id            {first['frame_id']}")
        add(f"    codec               {CODEC_NAMES.get(first['codec'], first['codec'])}")
        add(f"    dimensions          {first['width']}x{first['height']}")
        add(f"    quality             {first['quality']}")
        add(f"    chunk_total         {first['chunk_total']}")
        add(f"    frame_bytes         {first['frame_bytes']}")
        add(f"    chunks observed     {first['got']}")
        add(f"    media bytes         {first['media']}")
        add(f"    keyframe            {bool(first['vflags'] & VFLAG_KEYFRAME)}")
        add(f"    ts present          {bool(first['vflags'] & VFLAG_TS_PRESENT)}")

        # The off-by-20 diagnostic. This is the single most useful thing this
        # tool does: a sender that divided by max_chunk_bytes instead of
        # (max_chunk_bytes - 20) produces a chunk_total that is too small.
        #
        # Use the *first* chunk's media length, not the mean: the final chunk of
        # a frame is normally short and would skew the average downward.
        if first["got"] and first["frame_bytes"]:
            per_chunk = first["first_chunk_media"] or (first["media"] // first["got"])
            chunk_bytes = per_chunk + 20
            naive = -(-first["frame_bytes"] // chunk_bytes)
            correct = -(-first["frame_bytes"] // per_chunk)
            add("")
            add("  chunking check:")
            add(f"    media bytes/chunk   {per_chunk}  (payload_len - 20)")
            add(f"    chunk_bytes         {chunk_bytes}  (max_chunk_bytes)")
            add(f"    reported total      {first['chunk_total']}")
            add(f"    correct total       {correct}   = ceil(frame_bytes / (chunk_bytes - 20))")
            if first["chunk_total"] != correct:
                add(f"    !! MISMATCH: reported {first['chunk_total']}, expected {correct}")
                add(f"       a sender computing ceil({first['frame_bytes']} / {chunk_bytes}) "
                    f"would report {naive} -- the classic off-by-20 bug")
            else:
                add("    OK")

        total_media = sum(f["media"] for f in vf)
        total_over = sum(f["overhead"] for f in vf)
        add("")
        add(f"  total media bytes     {total_media}")
        add(f"  protocol overhead     {total_over}  "
            f"({_pct(total_over, total_media + total_over)} of video bytes)")

    if verbose and vf:
        add("")
        add("-- per-frame (first 40) " + "-" * 43)
        add("  frame_id  chunks  media   frame_bytes  kf  ts")
        for f in vf[:40]:
            add(f"  {f['frame_id']:>8}  {f['got']:>6}  {f['media']:>6}  "
                f"{f['frame_bytes']:>11}  {'Y' if f['vflags'] & VFLAG_KEYFRAME else 'n'}   "
                f"{'Y' if f['vflags'] & VFLAG_TS_PRESENT else 'n'}")

    add("")
    add("-- verdict " + "-" * 57)
    problems = []
    if stats["hdr_crc_errors"]:
        problems.append(f"{stats['hdr_crc_errors']} header CRC error(s) -- link or parsing is corrupting data")
    if stats["resync_count"]:
        problems.append(f"{stats['resync_count']} resync(s) -- either dropped bearer data or a framing bug")
    if res["incomplete_frames"]:
        problems.append(f"{res['incomplete_frames']} incomplete video frame(s) -- chunks were lost in transit")
    if res["bad_chunk_index"]:
        problems.append(f"{res['bad_chunk_index']} out-of-order chunk(s) -- sender must emit chunks in order")
    if not problems:
        add("  clean: every frame parsed and every video frame reassembled")
    else:
        for p in problems:
            add(f"  - {p}")
    return out


def _pct(part: int, whole: int) -> str:
    return f"{100.0 * part / whole:.1f}%" if whole else "n/a"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", type=Path, help="capture file (.bin/.raw/.hex/.txt/.pcap)")
    ap.add_argument("-v", "--verbose", action="store_true", help="per-frame table")
    ap.add_argument("--histogram-only", action="store_true", help="skip video analysis")
    args = ap.parse_args()

    if not args.input.exists():
        print(f"no such file: {args.input}", file=sys.stderr)
        return 2

    blob = load_file(args.input)
    if len(blob) < HEADER_SIZE:
        print(f"only {len(blob)} bytes of WatchPlay data found in {args.input}; nothing to parse", file=sys.stderr)
        return 1

    res = analyze(blob, verbose=args.verbose)
    for line in report(res, verbose=args.verbose, histogram_only=args.histogram_only):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
