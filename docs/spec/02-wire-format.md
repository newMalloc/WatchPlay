# 02 — 线格式（Wire Format）

本文件定义 WatchPlay 协议 v0.1 的**字节级线格式**。所有多字节整数均为**小端序（little-endian）**，所有结构体均**紧凑排列（packed，无填充字节）**。

## 1. 帧头（16 字节）

每一个协议帧都以固定 16 字节帧头开始。

所有偏移从帧的第一个字节算起。负载紧随帧头，长度为 `payload_len`。

| 偏移 | 长度 | 字段 | 类型 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| 0 | 2 | `magic` | u8[2] | 固定为 `0x57 0x50`，即 ASCII `"WP"` |
| 2 | 1 | `version` | u8 | 协议主版本，v0.1 为 `0x01` |
| 3 | 1 | `type` | u8 | 帧类型，见 [§3](#3-帧类型) |
| 4 | 2 | `flags` | u16 | 帧类型相关的标志位，见 [§4](#4-标志位) |
| 6 | 2 | `hdr_len` | u16 | 帧头总长度，当前固定为 `16` |
| 8 | 4 | `seq` | u32 | 序列号，单调递增，见 [§5](#5-序列号) |
| 12 | 2 | `payload_len` | u16 | 负载长度（字节），不含帧头 |
| 14 | 2 | `hdr_crc` | u16 | 帧头校验和，见 [§2](#2-crc16-校验) |
| 16 | … | `payload` | u8[] | 负载，长度 = `payload_len` |

设计说明：

- **`payload_len` 用 u16 而非 u32**：单个分块的负载不可能超过 65535 字节——视频大帧由 [04-video.md](04-video.md) 的分块机制拆分，且单块负载上限由协商值 `max_chunk_bytes` 约束（默认 244）。用 u16 让帧头收敛到 16 字节且所有字段严格 2 字节对齐，在 C 结构体和 Kotlin `ByteBuffer` 里都无需手动 padding 处理。
- **`hdr_crc` 是 u16 且只覆盖帧头**：计算范围是**整个 16 字节帧头**，其中 `hdr_crc` 所在的偏移 14–15 在校验时取 `0x00 0x00` 参与运算（因此输入恒为 16 字节，不是 14 字节）。不覆盖负载，因为：① BLE 链路层已有 CRC24 保护；② 参考硬件（ESP32-S3）要对每帧 30+ 个分块各算一次 244 字节的 CRC，是纯粹的 CPU 浪费；③ 负载的完整性由分块序号连续性 + 视频帧长校验来保证。**这一取舍是刻意的，不要"顺手优化"成负载 CRC。**
- 若 `hdr_crc` 校验失败，接收方 **必须（MUST）** 丢弃该帧并计入 `hdr_crc_errors` 统计，**禁止**尝试部分解析。

### 1.1 `hdr_len` 的作用

`hdr_len` 当前恒为 `16`。它的存在是为了**前向兼容**：未来若需要扩展帧头（例如加入 8 字节时间戳或扩展标志），新版本可以增大 `hdr_len`，而老实现读到 `hdr_len > 16` 时仍能定位 `payload` 起点。

接收方 **必须（MUST）**：

- 若 `hdr_len < 16`，视为畸形帧，丢弃。
- 若 `hdr_len > 16`，跳过 `hdr_len - 16` 字节的未知扩展区，然后读取负载。
- 不得假设 `hdr_len == 16` 之外的值不存在。

## 2. CRC16 校验

`hdr_crc` 使用 **CRC-16/CCITT-FALSE**：

| 参数 | 值 |
| :--- | :--- |
| 多项式 | `0x1021` |
| 初值 | `0xFFFF` |
| 输入反射 | 否 |
| 输出反射 | 否 |
| 结果异或 | `0x0000` |
| 标准校验值（输入 `"123456789"`） | `0x29B1` |

**计算范围**：输入是**完整的 16 字节帧头**，其中偏移 14–15（`hdr_crc` 自身）在校验时按 `0x00 0x00` 处理。

```python
def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc

def hdr_crc(header_16: bytes) -> int:
    return crc16_ccitt_false(header_16[:14] + b"\x00\x00")
```

> 附录不省略那两个零字节**不是**实现细节：CRC-16 每喂入一个字节都会推进寄存器，`crc16(h[:14])` 与 `crc16(h[:14] + b"\x00\x00")` 结果不同（见 §1.2 的 `0xF55D`）。只算 14 字节会得到 `0x7D5D`，对端一律判为坏帧。

> **为什么不是 CRC32？** 帧头只有 16 字节，CRC32 相对 CRC16 的检错能力提升在如此短的输入上可以忽略，而每帧省下的 2 字节对心跳帧这类高频小帧有意义。负载的完整性由传输层（BLE 链路层自带 CRC24）和视频层的分块序号连续性保证。

## 3. 帧类型

| 值 | 名称 | 方向 | 负载长度 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `0x01` | `HELLO` | Sink → Source | 变长 | 手表发起连接后立即发送，声明身份 |
| `0x02` | `HELLO_ACK` | Source → Sink | 变长 | 手机接受连接，回送自己的身份 |
| `0x03` | `CAPS` | Sink → Source | 变长 | 手表上报屏幕与解码能力 |
| `0x04` | `CAPS_ACK` | Source → Sink | 变长 | 手机回送**协商结果**（本会话最终参数） |
| `0x05` | `VIDEO` | Source → Sink | 变长 | 视频帧的一个分块，结构见 [04-video.md](04-video.md) |
| `0x06` | `VIDEO_END` | Source → Sink | 0 | 一帧的最后一个分块的简洁标记（可选优化，见下） |
| `0x07` | `TOUCH` | Sink → Source | 14 | 触控事件，结构见 [05-input.md](05-input.md) |
| `0x08` | `CONTROL` | 双向 | 变长 | 会话控制命令，见 [03-session.md](03-session.md) |
| `0x09` | `FEEDBACK` | Sink → Source | 22 | 接收端状态与 Credit 授予，结构见 [04-video.md §4.2](04-video.md) |
| `0x0A` | `PING` | 双向 | 0 或变长 | 心跳请求 |
| `0x0B` | `PONG` | 双向 | 0 或变长 | 心跳响应 |
| `0x0C` | `BYE` | 双向 | 变长 | 优雅断开，携带原因码 |
| `0x00` | *保留* | — | — | 禁止使用 |
| `0x0D`–`0x7F` | *保留* | — | — | 未来标准扩展，收到 **必须（MUST）** 忽略 |
| `0x80`–`0xFF` | *厂商私有* | — | — | 厂商自定义，收到 **必须（MUST）** 忽略 |

> **未知帧类型处理**：接收方遇到未定义的 `type` **必须（MUST）** 依据 `payload_len`/`hdr_len` 正确跳过整帧，并计入 `unknownFrames` 统计，**禁止**断开连接。这是厂商扩展和未来版本兼容的基础。

### 3.1 关于 `VIDEO_END`

一帧的结束由 `VIDEO` 分块头中的 `chunk_index == chunk_total - 1` 判定，**不需要** `VIDEO_END`。`VIDEO_END` 预留给"发送方希望显式标记帧结束"的实现，v0.1 的参考实现不发送它，接收方可以忽略。

## 4. 标志位

`flags` 是 u16 位域，含义随 `type` 变化。

### 4.1 通用位（所有帧类型）

| 位 | 掩码 | 名称 | 含义 |
| :--- | :--- | :--- | :--- |
| 0 | `0x0001` | `ACK_REQUIRED` | 本帧需要对方回一个 `CONTROL(ACK)`。仅用于控制帧，视频帧 **禁止（MUST NOT）** 置位（避免 ACK 风暴） |
| 1 | `0x0002` | `RETRANSMIT` | 本帧是重传（BLE 链路层不可靠性或应用层重传） |
| 2 | `0x0004` | `LAST_IN_BURST` | 发送方当前没有更多数据待发（用于让接收端优化休眠） |
| 3 | `0x0008` | `IDLE_HINT` | 发送方预计 500 ms 内无数据（手表可降频、关屏背光外的外设） |
| 4–14 | — | *保留* | **必须（MUST）** 置 0，接收方忽略 |
| 15 | `0x8000` | `VENDOR` | 厂商私有语义，接收方无法理解时 **应当（SHOULD）** 忽略该帧而非断开 |

### 4.2 视频帧专有位（`type = VIDEO`）

| 位 | 掩码 | 名称 | 含义 |
| :--- | :--- | :--- | :--- |
| 3 | `0x0008` | `KEYFRAME` | 本分块所属的帧是**关键帧**（JPEG 恒为真；H.264 为 IDR） |
| 4 | `0x0010` | `FIRST_CHUNK` | 本分块是所属帧的第 0 块（等价于视频头 `chunk_index == 0`） |
| 5 | `0x0020` | `LAST_CHUNK` | 本分块是所属帧的最后一块 |
| 6 | `0x0040` | `DISCONTINUITY` | 本帧之前有帧被判废，接收端 **应当（SHOULD）** 重置解码参考状态（H.264 场景） |

> `FIRST_CHUNK` / `LAST_CHUNK` 与视频头中的 `chunk_index` / `chunk_total` **冗余**。这是有意的：接收方在只看到单块（例如抓包、或丢块后重同步）时，无需解析负载即可判断帧边界。两者冲突时 **以视频头为准**。

## 5. 序列号

- `seq` 是 **u32**，每发送一个帧递增 1，回绕到 `0` 后从 1 继续（**跳过 0**，`0` 保留给"未初始化"）。
- `seq` 是**发送方向独立的**：手机的 `seq` 和手表的 `seq` 各算各的，互不干扰。
- 接收方 **必须（MUST）** 检测丢失：若收到 `seq` 与期望值不连续，计入 `lostFrames`，并继续处理（不得静默卡死）。
- `seq` 的回绕比较 **必须（MUST）** 使用 RFC 1982 序列号算术，禁止用朴素的 `>` 比较：

```python
def seq_after(a: int, b: int) -> bool:
    """a 是否在 b 之后（RFC 1982，u32 空间）。"""
    return 0 < ((a - b) & 0xFFFFFFFF) < 0x80000000
```

- **重传**：`RETRANSMIT` 位置位时，`seq` **必须（MUST）** 复用原帧的 `seq`，接收方据此去重。
- 视频分块共享其所属视频帧的 `seq`（见 [04-video.md](04-video.md)），因为分块顺序由视频头内的 `chunk_index` 保证。

## 6. 负载长度上限

| 上下文 | 上限 | 原因 |
| :--- | :--- | :--- |
| `HELLO` / `HELLO_ACK` | 128 字节 | 固定结构 + 有限 TLV |
| `CAPS` / `CAPS_ACK` | 128 字节 | 同上 |
| `TOUCH` | 14 字节 | 定长 |
| `FEEDBACK` | 22 字节 | 定长（字段表见 [04-video.md §4.2](04-video.md)） |
| `CONTROL` | 64 字节 | 定长命令 + 短参数 |
| `VIDEO` 单块负载 | **协商值**，默认 ≤ 244 字节 | 受 BLE MTU 限制，见 [01-transport.md](01-transport.md) |

单块负载的实际上限由 `CAPS_ACK.max_chunk_bytes` 决定（协商所得），发送方 **禁止（MUST NOT）** 超过。

## 7. 解析伪代码

接收方 **应当（SHOULD）** 按以下顺序处理任意入向字节流：

```python
def on_bytes(buf: bytearray) -> None:
    while len(buf) >= 16:
        hdr = buf[:16]
        magic, version, ftype, flags, hdr_len, seq, payload_len, hdr_crc = unpack_header(hdr)

        # 1. 魔数校验：失败则进入重新同步
        if magic != b"WP":
            resync(buf)          # 逐字节前移到下一个 "WP"
            continue

        # 2. 帧头长度合理性
        if hdr_len < 16 or hdr_len > 256:
            drop_and_resync(buf); continue

        # 3. 帧头 CRC（16 字节输入，偏移 14-15 置零）
        if crc16(hdr[:14] + b"\x00\x00") != hdr_crc:
            stats.hdr_crc_errors += 1
            drop_and_resync(buf); continue

        # 4. 版本
        if version != 1:
            send_bye(REASON_UNSUPPORTED_VERSION); close(); return

        # 5. 负载是否到齐
        total = hdr_len + payload_len
        if len(buf) < total:
            return                # 等待更多字节，保持缓冲

        # 6. 跳过未知帧头扩展区，取出负载
        payload = buf[hdr_len:total]
        deliver(ftype, flags, seq, payload)

        del buf[:total]
```

### 7.1 重新同步（Resync）

BLE 字节流可能因连接抖动而错位。`resync()` **必须（MUST）**：

1. 在缓冲中从前向后搜索下一个 `0x57 0x50` 序列。
2. 丢弃其之前的所有字节，并计入 `resyncCount`。
3. 若连续 `resyncCount` 在 5 秒内超过 32，**应当（SHOULD）** 判定链路异常并主动 `BYE(REASON_PROTOCOL_ERROR)` 断开重连。

### 7.2 背压

若应用层消费不过来（解码队列已满），传输层 **应当（SHOULD）** 停止从 socket/notify 回调取数据，而不是无界缓冲。缓冲队列 **禁止（MUST NOT）** 超过 `2 × 单帧最大字节数`。

## 8. 完整示例：一个 MJPEG 帧的字节序列

466×466 圆屏，一帧 JPEG 编码后 **8123 字节**，协商 `max_chunk_bytes = 244`。

**分块数计算（易错点）**：`max_chunk_bytes` 限制的是**协议帧的 `payload_len`**，而 `payload_len` **包含 20 字节的视频子头**（见 [04-video.md](04-video.md)）。因此每块实际可携带的 JPEG 数据是 `244 - 20 = 224` 字节，而不是 244：

```
chunk_total = ceil(frame_bytes / (max_chunk_bytes - 20))
            = ceil(8123 / 224)
            = 37
```

最后一块携带 `8123 - 36×224 = 59` 字节数据，`payload_len = 20 + 59 = 79`。

> 这是实现中最常见的 off-by-20 错误。用错误公式（`ceil(8123/244) = 34`）算出的 `chunk_total` 会让接收端在第 34 块就认为帧结束，从而截断图像。见 [04-video.md §3.1](04-video.md) 的断言要求。

### 第 0 块的真实字节

由 `tools/gen_traces.py` 生成，并已与提交的 trace 文件核对一致：

```
00000000  57 50 01 05 18 00 10 00 2A 00 00 00 F4 00 5D F5  WP......*.....].
00000010  07 00 00 00 00 00 25 00 01 01 D2 01 D2 01 48 00  ......%.......H.
00000020  BB 1F 00 00 <224 字节 JPEG 数据…>                     ............```

逐字段解释：

| 字节 | 字段 | 值 | 说明 |
| :--- | :--- | :--- | :--- |
| `57 50` | `magic` | `"WP"` | 固定 |
| `01` | `version` | 1 | v0.1 |
| `05` | `type` | 5 | `VIDEO` |
| `18 00` | `flags` | `0x0018` | `KEYFRAME(0x0008) \| FIRST_CHUNK(0x0010)` |
| `10 00` | `hdr_len` | 16 | |
| `2A 00 00 00` | `seq` | 42 | 视频分块共享所属帧的 `seq` |
| `F4 00` | `payload_len` | 244 | = 20 字节子头 + 224 字节 JPEG 数据 |
| `5D F5` | `hdr_crc` | `0xF55D` | CRC-16/CCITT-FALSE，16 字节帧头（偏移 14–15 置零） |
| `07 00 00 00` | v.`frame_id` | 7 | |
| `00 00` | v.`chunk_index` | 0 | |
| `25 00` | v.`chunk_total` | **37** | `ceil(8123 / 224)` |
| `01` | v.`codec` | 1 | MJPEG |
| `01` | v.`vflags` | `0x01` | bit0 = 关键帧 |
| `D2 01` | v.`width` | 466 | |
| `D2 01` | v.`height` | 466 | |
| `48` | v.`quality` | 72 | JPEG 质量 |
| `00` | v.`reserved` | 0 | 必须为 0 |
| `BB 1F 00 00` | v.`frame_bytes` | 8123 | 整帧编码后字节数 |

### 第 36 块（最后一块）

```
57 50 01 05 28 00 10 00 2A 00 00 00 4F 00 63 43 07 00 00 00
```

`flags = 0x0028`（`FIRST_CHUNK` 清零、`KEYFRAME` 与 `LAST_CHUNK` 置位），`payload_len = 0x4F = 79`，`hdr_crc = 0x4363`。

### 完整可执行校验

```python
from tools.watchplay_proto import StreamParser
parser = StreamParser()
frames = parser.feed(open("examples/protocol-traces/mjpeg-frame-466x466-3c.bin", "rb").read())
assert len(frames) == 37
assert parser.stats()["hdr_crc_errors"] == 0
jpeg = b"".join(f.payload[20:] for f in frames)
assert len(jpeg) == 8123
```

完整 trace 与 sha256 见 [`examples/protocol-traces/`](../../examples/protocol-traces/README.md)。

## 9. 变更控制

帧头布局和 CRC 算法在协议 **1.0 之前不得变更**；0.x 期间若必须变更，`version` 字段递增并同时提供兼容读路径。变更记录见 [99-changelog.md](99-changelog.md)。
