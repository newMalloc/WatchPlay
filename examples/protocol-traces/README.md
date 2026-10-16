# Protocol traces (generated)

> **AUTOGEN** — regenerate with `python tools/gen_traces.py`.
> These files are the ground truth for parser unit tests; do not hand-edit.

## `mjpeg-frame-466x466-3c.bin`

One complete MJPEG frame as it appears on the wire: 8123 bytes of
JPEG split into 37 VIDEO frames of at most 244 bytes payload.
`seq = 42`, `frame_id = 7`, 466x466, quality 72.

Total on-wire size: **9455 bytes**.

sha256: `5d0a112b564379602b9b9468e6d919073e081a30bc80ea167a7cc17a0d99c3b0`

### First chunk, byte by byte

```
  00000000  57 50 01 05 18 00 10 00 2A 00 00 00 F4 00 5D F5   WP......*.....].
  00000010  07 00 00 00 00 00 25 00 01 01 D2 01 D2 01 48 00   ......%.......H.
  00000020  BB 1F 00 00 11 30 4F 6E 8D AC CB EA 09 28 47 66   .....0On.....(Gf
  00000030  85 A4 C3 E2 01 20 3F 5E 7D 9C BB DA F9 18 37 56   ..... ?^}.....7V
```

| bytes | field | value |
| :--- | :--- | :--- |
| `57 50` | `magic` | "WP" |
| `01` | `version` | 1 |
| `05` | `type` | 5 (VIDEO) |
| `18 00` | `flags` | 0x0018 |
| `10 00` | `hdr_len` | 16 |
| `2A 00 00 00` | `seq` | 42 |
| `F4 00` | `payload_len` | 244 |
| `5D F5` | `hdr_crc` | 0xF55D |
| `—` | `*video sub-header begins*` |  |
| `07 00 00 00` | `frame_id` | 7 |
| `00 00` | `chunk_index` | 0 |
| `25 00` | `chunk_total` | 37 |
| `01` | `codec` | 1 (MJPEG) |
| `01` | `vflags` | 0x01 |
| `D2 01` | `width` | 466 |
| `D2 01` | `height` | 466 |
| `48` | `quality` | 72 |
| `00` | `reserved` | 0 |
| `BB 1F 00 00` | `frame_bytes` | 8123 |

### Last chunk header

```
  00000000  57 50 01 05 28 00 10 00 2A 00 00 00 4F 00 63 43   WP..(...*...O.cC
  00000010  07 00 00 00 24 00 25 00 01 01 D2 01 D2 01 48 00   ....$.%.......H.
```

Note `flags` is now `0x0028` (`LAST_CHUNK` set, `FIRST_CHUNK` clear) and `payload_len` is 79 — the remainder of the frame.

## `session-handshake.bin`

A minimal happy-path session: `HELLO`, `HELLO_ACK`, `CAPS`, `CAPS_ACK`,
`PING`, `PONG`. Payloads here are illustrative placeholders — the real
structures are defined in [03-session.md](../../docs/spec/03-session.md) and
[07-capabilities.md](../../docs/spec/07-capabilities.md).

Total: **229 bytes**.

sha256: `52cf7796d95561d3371c5efdf46c30ee51d08d86971742759d93390d36cae546`

```
  00000000  57 50 01 01 00 00 10 00 01 00 00 00 1A 00 7F 57   WP.............W
  00000010  57 50 48 45 4C 4C 4F 01 74 64 69 73 70 6C 61 79   WPHELLO.tdisplay
  00000020  2D 73 33 2D 61 6D 6F 6C 65 64 57 50 01 02 00 00   -s3-amoledWP....
  00000030  10 00 01 00 00 00 1B 00 6E EE 57 50 48 45 4C 4C   ........n.WPHELL
  00000040  4F 5F 41 43 4B 01 61 6E 64 72 6F 69 64 2D 73 64   O_ACK.android-sd
  00000050  6B 2D 30 2E 31 57 50 01 03 00 00 10 00 02 00 00   k-0.1WP.........
```

## Conformance

A conforming parser must reassemble all 37 chunks into exactly one frame of
8123 bytes with **zero** CRC errors and **zero** resyncs:

```python
from tools.watchplay_proto import StreamParser
parser = StreamParser()
frames = parser.feed(open('examples/protocol-traces/mjpeg-frame-466x466-3c.bin','rb').read())
assert len(frames) == 37
assert parser.stats()['hdr_crc_errors'] == 0
```

## CRC-16 spot checks

| input | expected `hdr_crc` for a zeroed header |
| :--- | :--- |
| `'123456789'` | `0x29B1` |
| `'WP'` | `0xD0D2` |
| `''` | `0xFFFF` |

`crc16_ccitt_false(b"123456789") == 0x29B1` is the standard check value and is
asserted in `tools/test_protocol.py`.
