"""Conformance tests for the WatchPlay v0.1 wire format.

Run:  python -m pytest tools/test_protocol.py -q
  or: python tools/test_protocol.py

These tests are the machine-checkable half of docs/spec/. If a test here has to
change to make something pass, the *spec* changed too -- update both.
"""

from __future__ import annotations

import ast
import math
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from watchplay_proto import (  # noqa: E402
    BEARER_BT_SPP,
    BEARER_GATT,
    BEARER_L2CAP_COC,
    BEARER_WIFI_TCP,
    DEFAULT_BT_SPP_BEARER_TU,
    DEFAULT_INITIAL_CREDITS,
    DEFAULT_MAX_CHUNK_BYTES,
    DEFAULT_ROUND_SAFE_INSET_PERMILLE,
    DEFAULT_SQUARE_SAFE_INSET_PERMILLE,
    FEEDBACK_FIELDS,
    FEEDBACK_PAYLOAD_BYTES,
    HEADER_SIZE,
    HELLO_ACK_PAYLOAD_BYTES,
    HELLO_PAYLOAD_BYTES,
    MAGIC,
    PROTOCOL_VERSION,
    RESULT_TLV_BYTES,
    STREAM_TLV_BYTES,
    TOUCH_FIELDS,
    TOUCH_PAYLOAD_BYTES,
    VENDOR_TYPE_BASE,
    VIDEO_CODEC_H264,
    VIDEO_CODEC_MJPEG,
    VIDEO_SUBHEADER_FIELDS,
    VIDEO_SUBHEADER_SIZE,
    Flags,
    FrameType,
    Header,
    ParseError,
    StreamParser,
    crc16_ccitt_false,
    encode,
    parse_header,
    seq_after,
    seq_next,
)

REPO = Path(__file__).resolve().parent.parent
TRACES = REPO / "examples" / "protocol-traces"


class TestCrc16(unittest.TestCase):
    """spec 02 section 2."""

    def test_standard_check_value(self):
        # The canonical CRC-16/CCITT-FALSE check value. If this breaks, the
        # polynomial or init is wrong and *nothing* else will interoperate.
        self.assertEqual(crc16_ccitt_false(b"123456789"), 0x29B1)

    def test_empty(self):
        self.assertEqual(crc16_ccitt_false(b""), 0xFFFF)

    def test_known_vectors(self):
        self.assertEqual(crc16_ccitt_false(b"A"), 0xB915)
        self.assertEqual(crc16_ccitt_false(b"WP"), 0xD0D2)

    def test_not_crc16_arc(self):
        # Guard against accidentally using a reflected variant (ARC/XMODEM).
        self.assertNotEqual(crc16_ccitt_false(b"123456789"), 0xBB3D)
        self.assertNotEqual(crc16_ccitt_false(b"123456789"), 0x31C3)


class TestHeader(unittest.TestCase):
    """spec 02 section 1."""

    def test_size_is_exactly_16(self):
        self.assertEqual(len(Header(type=FrameType.PING, seq=1).pack()), HEADER_SIZE)
        self.assertEqual(HEADER_SIZE, 16)

    def test_field_offsets(self):
        raw = encode(FrameType.TOUCH, seq=0xDEADBEEF, payload=b"\x00" * 14, flags=Flags.ACK_REQUIRED)
        self.assertEqual(raw[0:2], MAGIC)
        self.assertEqual(raw[2], PROTOCOL_VERSION)
        self.assertEqual(raw[3], FrameType.TOUCH)
        self.assertEqual(int.from_bytes(raw[4:6], "little"), Flags.ACK_REQUIRED)
        self.assertEqual(int.from_bytes(raw[6:8], "little"), 16)
        self.assertEqual(int.from_bytes(raw[8:12], "little"), 0xDEADBEEF)
        self.assertEqual(int.from_bytes(raw[12:14], "little"), 14)
        self.assertEqual(len(raw), 16 + 14)

    def test_little_endian(self):
        raw = encode(FrameType.PING, seq=0x00000001)
        self.assertEqual(raw[8:12], b"\x01\x00\x00\x00")

    def test_crc_covers_offsets_0_to_13_only(self):
        # Changing the payload MUST NOT change hdr_crc.
        a = encode(FrameType.VIDEO, seq=5, payload=b"aaaa")
        b = encode(FrameType.VIDEO, seq=5, payload=b"bbbb")
        self.assertEqual(a[14:16], b[14:16])
        # Changing a header field MUST change hdr_crc.
        c = encode(FrameType.VIDEO, seq=6, payload=b"aaaa")
        self.assertNotEqual(a[14:16], c[14:16])

    def test_crc_field_is_zeroed_during_computation(self):
        raw = encode(FrameType.PING, seq=7)
        expect = crc16_ccitt_false(raw[0:14] + b"\x00\x00")
        self.assertEqual(int.from_bytes(raw[14:16], "little"), expect)

    def test_roundtrip(self):
        h = Header(type=FrameType.CAPS_ACK, seq=123456, flags=Flags.LAST_IN_BURST, payload_len=48)
        parsed = parse_header(h.pack())
        self.assertEqual(parsed.type, h.type)
        self.assertEqual(parsed.seq, h.seq)
        self.assertEqual(parsed.flags, h.flags)
        self.assertEqual(parsed.payload_len, 48)
        self.assertEqual(parsed.version, PROTOCOL_VERSION)

    def test_bad_magic_rejected(self):
        raw = bytearray(encode(FrameType.PING, seq=1))
        raw[0] = 0x58
        with self.assertRaises(ParseError):
            parse_header(bytes(raw))

    def test_corrupt_header_rejected(self):
        raw = bytearray(encode(FrameType.PING, seq=1))
        raw[8] ^= 0xFF  # flip a seq bit, leave crc stale
        with self.assertRaises(ParseError):
            parse_header(bytes(raw))

    def test_implausible_hdr_len_rejected(self):
        raw = bytearray(encode(FrameType.PING, seq=1))
        raw[6:8] = (8).to_bytes(2, "little")
        with self.assertRaises(ParseError):
            parse_header(bytes(raw))

    def test_forward_compatible_hdr_len_skipped(self):
        """spec 02 section 1.1: hdr_len > 16 must skip the unknown extension."""
        h = Header(type=FrameType.PING, seq=9, hdr_len=24, payload_len=3)
        raw = h.pack() + b"abc"
        self.assertEqual(len(raw), 27)
        parsed = parse_header(raw)
        self.assertEqual(parsed.hdr_len, 24)
        # payload starts after the extension region
        self.assertEqual(raw[parsed.hdr_len :], b"abc")


class TestSeqArithmetic(unittest.TestCase):
    """spec 02 section 5 -- RFC 1982 serial arithmetic."""

    def test_basic(self):
        self.assertTrue(seq_after(2, 1))
        self.assertFalse(seq_after(1, 2))

    def test_wraparound(self):
        # 0xFFFFFFFF -> 1 (0 is skipped)
        self.assertTrue(seq_after(1, 0xFFFFFFFF))
        self.assertFalse(seq_after(0xFFFFFFFF, 1))

    def test_naive_comparison_would_fail(self):
        self.assertTrue(seq_after(1, 0xFFFFFFF0))

    def test_next_skips_zero(self):
        self.assertEqual(seq_next(0xFFFFFFFF), 1)
        self.assertEqual(seq_next(1), 2)
        self.assertEqual(seq_next(0), 1)


class TestStreamParser(unittest.TestCase):
    """spec 02 section 7 -- incremental parsing and resync."""

    def test_single_frame(self):
        p = StreamParser()
        frames = p.feed(encode(FrameType.PING, seq=1))
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].type, FrameType.PING)
        self.assertEqual(p.stats()["hdr_crc_errors"], 0)

    def test_multiple_frames_one_buffer(self):
        blob = b"".join(encode(FrameType.PING, seq=i) for i in range(1, 6))
        p = StreamParser()
        self.assertEqual(len(p.feed(blob)), 5)

    def test_byte_at_a_time(self):
        """The parser must survive arbitrary fragmentation of the byte stream."""
        blob = b"".join(encode(FrameType.PONG, seq=i, payload=bytes([i]) * i) for i in range(1, 8))
        p = StreamParser()
        total = 0
        for i in range(len(blob)):
            total += len(p.feed(blob[i : i + 1]))
        self.assertEqual(total, 7)
        self.assertEqual(p.stats()["hdr_crc_errors"], 0)

    def test_unknown_frame_type_is_skipped_not_fatal(self):
        """spec 02 section 3: unknown types MUST be ignored, not fatal."""
        blob = encode(0x80, seq=1, payload=b"vendor!") + encode(FrameType.PING, seq=2)
        p = StreamParser()
        frames = p.feed(blob)
        self.assertEqual(len(frames), 2)
        self.assertEqual(p.stats()["unknown_frames"], 1)
        self.assertEqual(frames[1].type, FrameType.PING)

    def test_leading_garbage_resyncs(self):
        blob = b"\x11\x22\x33" + encode(FrameType.PING, seq=1)
        p = StreamParser()
        frames = p.feed(blob)
        self.assertEqual(len(frames), 1)
        self.assertGreaterEqual(p.stats()["resync_count"], 1)

    def test_corrupt_frame_then_recovery(self):
        good = encode(FrameType.PING, seq=2)
        bad = bytearray(encode(FrameType.PING, seq=1))
        bad[8] ^= 0xFF
        p = StreamParser()
        frames = p.feed(bytes(bad) + good)
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].seq, 2)
        self.assertGreaterEqual(p.stats()["hdr_crc_errors"], 1)

    def test_empty_payload_frame(self):
        p = StreamParser()
        frames = p.feed(encode(FrameType.BYE, seq=1))
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].payload, b"")


class TestGeneratedTraces(unittest.TestCase):
    """The committed traces in examples/ must parse exactly as documented.

    `skipTest` is deliberately NOT used for the two normative trace files. An
    earlier revision skipped when a trace was absent, which let a `.gitignore`
    rule that swallowed `*.bin` turn a real CI failure into a green local run --
    the traces were never committed at all and nothing complained. For files the
    spec depends on, absent is a failure, not a skip.
    """

    NORMATIVE = ("mjpeg-frame-466x466-3c.bin", "session-handshake.bin")

    def test_normative_traces_are_present_and_tracked(self):
        for name in self.NORMATIVE:
            path = TRACES / name
            with self.subTest(trace=name):
                self.assertTrue(path.exists(), f"{path} is missing; run tools/gen_traces.py")
                self.assertGreater(path.stat().st_size, 0, f"{path} is empty")

    def test_normative_traces_are_tracked_by_git(self):
        """A trace that exists locally but is git-ignored will not reach CI.

        This is the exact failure that shipped: `.gitignore` had a bare `*.bin`
        meant for ESP-IDF firmware images, which silently excluded the traces
        from every commit while they sat happily in the working tree.
        """
        import subprocess

        if not (REPO / ".git").exists():
            self.skipTest("not a git worktree (e.g. source tarball)")
        result = subprocess.run(
            ["git", "ls-files", "--error-unmatch", *[f"examples/protocol-traces/{n}" for n in self.NORMATIVE]],
            cwd=REPO,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(
            result.returncode,
            0,
            "normative traces are not tracked by git -- they will be missing in CI. "
            f"Likely a .gitignore rule is excluding them.\n{result.stderr.strip()}",
        )

    def _load(self, name: str) -> bytes:
        p = TRACES / name
        self.assertTrue(p.exists(), f"{p} missing; run tools/gen_traces.py")
        return p.read_bytes()

    def test_mjpeg_frame_reassembles(self):
        blob = self._load("mjpeg-frame-466x466-3c.bin")
        p = StreamParser()
        frames = p.feed(blob)
        self.assertEqual(p.stats()["hdr_crc_errors"], 0)
        self.assertEqual(p.stats()["resync_count"], 0)
        self.assertTrue(all(f.type == FrameType.VIDEO for f in frames))

        # every chunk agrees on frame identity and total
        totals = {int.from_bytes(f.payload[6:8], "little") for f in frames}
        self.assertEqual(len(totals), 1)
        self.assertEqual(totals.pop(), len(frames))

        # reassemble the JPEG: strip the 20-byte video sub-header from each chunk
        body = b"".join(f.payload[20:] for f in frames)
        declared = int.from_bytes(frames[0].payload[16:20], "little")
        self.assertEqual(len(body), declared)

        # chunk indices are exactly 0..N-1 in order
        indices = [int.from_bytes(f.payload[4:6], "little") for f in frames]
        self.assertEqual(indices, list(range(len(frames))))

        # chunking rule: payload_len <= max_chunk_bytes, sub-header is 20
        for f in frames:
            self.assertLessEqual(len(f.payload), 244)
        for f in frames[:-1]:
            self.assertEqual(len(f.payload), 244)

    def test_first_and_last_chunk_flags(self):
        blob = self._load("mjpeg-frame-466x466-3c.bin")
        frames = StreamParser().feed(blob)
        first, last = frames[0], frames[-1]
        self.assertTrue(first.flags & Flags.FIRST_CHUNK)
        self.assertFalse(first.flags & Flags.LAST_CHUNK)
        self.assertTrue(first.flags & Flags.KEYFRAME)
        self.assertTrue(last.flags & Flags.LAST_CHUNK)
        self.assertFalse(last.flags & Flags.FIRST_CHUNK)

    def test_session_trace(self):
        blob = self._load("session-handshake.bin")
        frames = StreamParser().feed(blob)
        self.assertEqual(
            [f.type for f in frames],
            [
                FrameType.HELLO,
                FrameType.HELLO_ACK,
                FrameType.CAPS,
                FrameType.CAPS_ACK,
                FrameType.PING,
                FrameType.PONG,
            ],
        )


class TestVideoChunkingMath(unittest.TestCase):
    """spec 04 -- the chunking rule quoted in spec 02 section 8."""

    def test_chunk_count_includes_subheader(self):
        subheader = 20
        max_payload = 244
        frame_bytes = 8123
        per_chunk = max_payload - subheader
        n = -(-frame_bytes // per_chunk)  # ceil
        self.assertEqual(n, 37)
        self.assertEqual((n - 1) * per_chunk, 36 * 224)
        self.assertEqual(frame_bytes - (n - 1) * per_chunk, 59)

    def test_small_frame_is_one_chunk(self):
        subheader, max_payload = 20, 244
        frame_bytes = 100
        n = max(1, -(-frame_bytes // (max_payload - subheader)))
        self.assertEqual(n, 1)


class TestFrozenConstants(unittest.TestCase):
    """Pins the protocol constants against docs/spec/.

    Every assertion here cites the section that defines it. If one of these
    fails, either the spec changed (update both) or someone quietly renumbered
    a wire value -- which would silently break every existing implementation.
    Wire values are not refactorable: they are burned into shipped firmware.
    """

    def test_frame_type_values(self):
        # docs/spec/02-wire-format.md section 3
        self.assertEqual(FrameType.HELLO, 0x01)
        self.assertEqual(FrameType.HELLO_ACK, 0x02)
        self.assertEqual(FrameType.CAPS, 0x03)
        self.assertEqual(FrameType.CAPS_ACK, 0x04)
        self.assertEqual(FrameType.VIDEO, 0x05)
        self.assertEqual(FrameType.VIDEO_END, 0x06)
        self.assertEqual(FrameType.TOUCH, 0x07)
        self.assertEqual(FrameType.CONTROL, 0x08)
        self.assertEqual(FrameType.FEEDBACK, 0x09)
        self.assertEqual(FrameType.PING, 0x0A)
        self.assertEqual(FrameType.PONG, 0x0B)
        self.assertEqual(FrameType.BYE, 0x0C)

    def test_generic_flag_values(self):
        # docs/spec/02-wire-format.md section 4.1
        self.assertEqual(Flags.ACK_REQUIRED, 0x0001)
        self.assertEqual(Flags.RETRANSMIT, 0x0002)
        self.assertEqual(Flags.LAST_IN_BURST, 0x0004)
        self.assertEqual(Flags.IDLE_HINT, 0x0008)
        self.assertEqual(Flags.VENDOR, 0x8000)

    def test_video_flag_values(self):
        # docs/spec/02-wire-format.md section 4.2
        self.assertEqual(Flags.KEYFRAME, 0x0008)
        self.assertEqual(Flags.FIRST_CHUNK, 0x0010)
        self.assertEqual(Flags.LAST_CHUNK, 0x0020)
        self.assertEqual(Flags.DISCONTINUITY, 0x0040)

    def test_codec_ids(self):
        # docs/spec/04-video.md section 1
        self.assertEqual(VIDEO_CODEC_MJPEG, 1)
        self.assertEqual(VIDEO_CODEC_H264, 2)

    def test_sizes(self):
        # docs/spec/02-wire-format.md sections 1 and 6
        self.assertEqual(HEADER_SIZE, 16)
        self.assertEqual(VIDEO_SUBHEADER_SIZE, 20)
        self.assertEqual(DEFAULT_MAX_CHUNK_BYTES, 244)
        # docs/spec/03-session.md section 1
        self.assertEqual(TOUCH_PAYLOAD_BYTES, 14)
        self.assertEqual(FEEDBACK_PAYLOAD_BYTES, 22)
        self.assertEqual(HELLO_PAYLOAD_BYTES, 52)
        self.assertEqual(HELLO_ACK_PAYLOAD_BYTES, 24)
        self.assertEqual(RESULT_TLV_BYTES, 24)
        self.assertEqual(STREAM_TLV_BYTES, 12)

    def test_field_tables_sum_to_declared_sizes(self):
        """A declared size must equal its field table's sum.

        This is the check whose absence let FEEDBACK ship labelled 20 bytes
        while its own table summed to 22 -- which would overrun a 20-byte
        buffer when writing `last_frame_id` at offset 18. Never assert a size
        by memory; derive it.
        """
        for label, declared, fields in (
            ("FEEDBACK", FEEDBACK_PAYLOAD_BYTES, FEEDBACK_FIELDS),
            ("TOUCH", TOUCH_PAYLOAD_BYTES, TOUCH_FIELDS),
            ("VIDEO sub-header", VIDEO_SUBHEADER_SIZE, VIDEO_SUBHEADER_FIELDS),
        ):
            with self.subTest(table=label):
                total = sum(width for _name, width in fields)
                self.assertEqual(
                    total,
                    declared,
                    f"{label}: field table sums to {total} but the spec declares {declared}",
                )

    def test_field_offsets_follow_the_table(self):
        # Offsets are cumulative and start at 0; no implicit padding anywhere.
        for label, fields in (
            ("FEEDBACK", FEEDBACK_FIELDS),
            ("TOUCH", TOUCH_FIELDS),
            ("VIDEO sub-header", VIDEO_SUBHEADER_FIELDS),
        ):
            with self.subTest(table=label):
                offset = 0
                for name, width in fields:
                    self.assertIn(width, (1, 2, 4), f"{label}.{name} has a non-wire width {width}")
                    offset += width
                self.assertEqual(offset, sum(w for _n, w in fields))

    def test_vendor_type_boundary(self):
        # docs/spec/02-wire-format.md section 3
        self.assertEqual(VENDOR_TYPE_BASE, 0x80)
        self.assertLess(max(int(t) for t in FrameType), VENDOR_TYPE_BASE)

    def test_default_credit_window(self):
        # docs/spec/03-session.md section 4 / docs/spec/04-video.md section 4.1
        self.assertEqual(DEFAULT_INITIAL_CREDITS, 64)

    def test_bearer_bitmap(self):
        # docs/spec/07-capabilities.md section 2.2
        self.assertEqual(BEARER_GATT, 0x01)
        self.assertEqual(BEARER_L2CAP_COC, 0x02)
        self.assertEqual(BEARER_WIFI_TCP, 0x04)
        self.assertEqual(BEARER_BT_SPP, 0x08)
        # A peer sets bits; it never writes a bearer index. Duplicates would make
        # "bit set" ambiguous, so every bearer owns exactly one bit.
        bits = (BEARER_GATT, BEARER_L2CAP_COC, BEARER_WIFI_TCP, BEARER_BT_SPP)
        self.assertEqual(len(set(bits)), len(bits))

    def test_bt_spp_bearer_tu_does_not_fit_the_u8_mtu_field(self):
        # docs/spec/07-capabilities.md section 2.2: TRANSPORT.mtu is a u8
        # truncated at 255 because it was sized for BLE. The classic-Bluetooth
        # bearer's bearer_tu is larger, so it must travel in the ext TLV --
        # an implementation that stuffs it into `mtu` silently reports 990 & 0xFF.
        self.assertGreater(DEFAULT_BT_SPP_BEARER_TU, 255)

    def test_round_safe_area_hard_limit(self):
        # docs/spec/06-rendering.md section 2: the largest centred axis-aligned
        # square inside a circle of diameter S has side S/sqrt(2), so the inset
        # per edge is (1 - 1/sqrt(2))/2 = 0.1464466..., i.e. 146.447 permille.
        hard_limit = (1.0 - 1.0 / math.sqrt(2.0)) / 2.0
        self.assertAlmostEqual(hard_limit, 0.1464466, places=6)
        self.assertAlmostEqual(hard_limit * 1000.0, 146.447, places=2)
        # The shipped default must sit below the hard limit, or content clips.
        self.assertLess(DEFAULT_ROUND_SAFE_INSET_PERMILLE, hard_limit * 1000.0)
        self.assertEqual(DEFAULT_ROUND_SAFE_INSET_PERMILLE, 140)
        self.assertEqual(DEFAULT_SQUARE_SAFE_INSET_PERMILLE, 40)


class TestExampleScenes(unittest.TestCase):
    """The scenes in examples/watch-ui/ are advertised as conformance examples.

    If they violate the rendering spec, either the scene or the spec is wrong --
    and a reader who copies the example will ship the bug.
    """

    SCENES = REPO / "examples" / "watch-ui"

    def test_scenes_exist(self):
        scenes = list(self.SCENES.glob("*.html")) if self.SCENES.exists() else []
        self.assertTrue(scenes, f"no scene files found under {self.SCENES}")

    def test_scenes_pass_the_linter(self):
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import lint_scenes

        for scene in sorted(self.SCENES.glob("*.html")):
            errors, _warnings = lint_scenes.lint_scene(scene)
            self.assertEqual(errors, [], f"{scene.name} violates the rendering spec: {errors}")

    def test_a_script_may_build_a_css_url_at_runtime(self):
        """`<script>` is JavaScript, not CSS: `url(...)` assembled there is not a fetch.

        The demo scene sets album art with `style.backgroundImage = "url(" + value + ")"`,
        where `value` is a `data:` URI pushed by the phone. No stylesheet requested that
        resource, so the external-resource rule must not fire on it.
        """
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import lint_scenes

        document = (
            "<!doctype html><html><head><style>"
            ":root{ --wp-safe-inset:14vmin }"
            "html,body{ background:#05070c; overflow:hidden }"
            "</style></head><body>"
            '<script>var slot = null; slot.style.backgroundImage = "url(" + value + ")";</script>'
            "</body></html>"
        )
        with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False, encoding="utf-8") as handle:
            handle.write(document)
            scene = Path(handle.name)
        self.addCleanup(scene.unlink)

        errors, _warnings = lint_scenes.lint_scene(scene)

        self.assertEqual(errors, [])


class TestDocumentation(unittest.TestCase):
    """The spec is only useful if its own cross-references work."""

    def test_doc_links_and_anchors_resolve(self):
        import check_doc_links

        rc = check_doc_links.main()
        self.assertEqual(rc, 0, "docs/ has broken relative links or anchors (see output above)")


class TestToolsAreStdlibOnly(unittest.TestCase):
    """tools/ is the executable reference implementation of the spec.

    It must run on a bare Python with no install step, so it may not import
    anything outside the standard library. Enforced here rather than in CI shell
    so the rule travels with the repo and fails with a readable message.
    """

    ALLOWED = {
        "__future__",
        "argparse",
        "ast",
        "base64",
        "collections",
        "check_doc_links",
        "dataclasses",
        "enum",
        "hashlib",
        "lint_scenes",
        "math",
        "pathlib",
        "re",
        "struct",
        "subprocess",
        "sys",
        "tempfile",
        "time",
        "typing",
        "unicodedata",
        "unittest",
        "urllib",
        "watchplay_proto",
    }

    def test_no_third_party_imports(self):
        offenders = []
        for py in sorted(Path(__file__).resolve().parent.glob("*.py")):
            tree = ast.parse(py.read_text(encoding="utf-8"), filename=str(py))
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        root = alias.name.split(".")[0]
                        if root not in self.ALLOWED:
                            offenders.append(f"{py.name}:{node.lineno} import {alias.name}")
                elif isinstance(node, ast.ImportFrom):
                    if node.level:  # relative import within tools/
                        continue
                    root = (node.module or "").split(".")[0]
                    if root and root not in self.ALLOWED:
                        offenders.append(f"{py.name}:{node.lineno} from {node.module} import ...")
        self.assertEqual(offenders, [], "tools/ must use only the standard library")


if __name__ == "__main__":
    unittest.main(verbosity=2)
