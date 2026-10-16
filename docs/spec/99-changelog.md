# 99 — 变更记录（Changelog）

本文件记录 WatchPlay **协议**的变更。实现代码的变更请见仓库根目录的 git 历史；协议的变更 **必须（MUST）** 在此登记，因为协议是跨组织、跨硬件的契约。

格式参考 [Keep a Changelog](https://keepachangelog.com/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)，兼容规则见 [07-capabilities.md §4.3](07-capabilities.md)。

## [未发布]

### 计划中（v0.2 候选）

见 [07-capabilities.md §5](07-capabilities.md) 的开放问题表。摘录：

- 捕获时间戳从可选改为**必须**（`V_TS_PRESENT`）
- 新增 `CAPS_SOURCE` TLV，让手机也上报完整能力
- 传感器数据通道（心率/加速度计的标准 TLV）
- 传输切换（`SWITCH_TRANSPORT`）的原子性与丢帧边界定义
- `CAPS` 总长度上限从 128 提升到 256

## [0.1.0] — 未发布

首个公开草案。**用于 M1（MJPEG 端到端 Demo）**。

### 起草期间已修正的错误（保留记录，避免重犯）

协议在起草过程中发现并修正了五处会导致实现互不兼容的错误。记录在此是因为这些错误很容易在实现时被"重新发明"：

| # | 错误 | 后果 | 修正 |
| :--- | :--- | :--- | :--- |
| 1 | 分块数按 `ceil(frame_bytes / max_chunk_bytes)` 计算 | 8123 字节 @ 244 得 34 块而非 37 块；接收端在第 34 块就认为帧结束，解出被截断的 JPEG | 改为 `ceil(frame_bytes / (max_chunk_bytes - 20))`，因为 `max_chunk_bytes` 限制的是**含 20 字节视频子头的 `payload_len`**。见 [02-wire-format.md §8](02-wire-format.md)、[04-video.md §3.1](04-video.md) |
| 2 | 帧头校验写成 **CRC32** | 帧头定长 16 字节装不下 u32 校验：`payload_len` 用 u16 且 CRC 用 u16 才能刚好对齐 | 统一为 **CRC-16/CCITT-FALSE**（u16，仅覆盖偏移 0–13）。见 [02-wire-format.md §2](02-wire-format.md) |
| 3 | `00-overview.md` 在别处仍写 "CRC32"，且把"传输缓冲 < 2 MB"含混描述 | 两处描述互相矛盾，实现者会二选一从而选错 | 以 [02-wire-format.md](02-wire-format.md) 为唯一权威；缓冲区改称"帧头层承载缓冲 520 字节"与"视频层整帧重组缓冲"两个独立概念 |
| 4 | `max_chunk_bytes` 一度被写成 `MTU − 3`（即 ATT 可用负载），与"协议帧需一次装进一次提交"的目标不符 | 两者混用会让每个协议帧超出承载单元从而被分片，或在 MTU 23 时算出 7 却仍硬撑到 84 | 明确 `max_chunk_bytes = MTU − 16`（刻意留 13 字节余量）。分片本身合法但有代价；**MTU < 100 时协商必须失败**（`BANDWIDTH_INSUFFICIENT`）而非静默降级。见 [03-session.md §4.2](03-session.md)、[01-transport.md §5](01-transport.md) |
| 5 | [01-transport.md](01-transport.md) 起草时以初始 Credit = 32 为默认，与 [03-session.md §4](03-session.md) 的 `initial_credits = 64` 冲突 | 两处给出不同窗口，手表与手机会对不上 | 统一为 **64**，并以 `CAPS_ACK.RESULT.initial_credits` 为唯一权威来源；`FEEDBACK.credits` 明确为**绝对值**而非增量 |

**给实现者的提醒**：以上第 1 条是本协议最容易犯的 off-by-20 错误。`tools/analyze_stream.py` 内置了自动诊断——它会从抓到的字节流反推 `chunk_total` 是否自相矛盾，并直接指出犯的是哪个公式。

### 协议核心

- 定义 16 字节定长帧头：`magic`/`version`/`type`/`flags`/`hdr_len`/`seq`/`payload_len`/`hdr_crc`
- 校验算法选定 **CRC-16/CCITT-FALSE**，仅覆盖帧头（偏移 0–13）
- `seq` 使用 RFC 1982 序列号算术；`seq = 0` 保留为"未初始化"
- `hdr_len > 16` 时跳过未知扩展区，为帧头扩展留出前向兼容路径
- 13 个标准 `FrameType`（`0x01`–`0x0C` 已分配，`0x06 VIDEO_END` 保留未用），并划分保留区（`0x0D`–`0x7F`）与厂商区（`0x80`–`0xFF`）

### 会话

- 定义 7 态状态机：`IDLE` → `CONNECTING` → `HANDSHAKING` → `NEGOTIATING` → `STREAMING` ⇄ `DRAINING` → `CLOSED`/`FAILED`
- **接收端（手表）为连接发起方**
- `HELLO`（52 字节）/ `HELLO_ACK`（24 字节），含 `nonce` 配对校验
- 版本协商规则：双方取最高共同版本
- `CAPS` / `CAPS_ACK` 采用 TLV 列表，而非定长结构
- 14 个 `CONTROL` opcode
- 心跳：默认 2 s 间隔、1 s 单次超时、连续 3 次丢失判死；有视频流量时抑制心跳
- 12 个 `BYE` 原因码
- 指数退避重连，`REJECTED` 时禁止自动重试

### 视频

- 双 Profile：**A = MJPEG（必须支持）**，**B = H.264（可选）**
- 20 字节视频子头
- 分块公式：`chunk_total = ceil(frame_bytes / (max_chunk_bytes - 20))`
  - `max_chunk_bytes` 默认 **244**，即每块 224 字节媒体数据
- **Credit 流控**：1 Credit = 1 分块，默认初始 64
- `FEEDBACK`（20 字节）归还 Credit 并上报解码统计
- MJPEG 丢块 → 丢整帧；H.264 丢帧 → 2 帧内产生 IDR，SPS/PPS 内联于 IDR
- H.264 要求：Baseline、无 B 帧、VBR、GOP `clamp(round(fps × 0.8), 8, 12)`、剥离起始码
- 码率自适应启发式与硬约束（质量 40–85，帧率 1–30）
- 启动前 3 帧使用低 20% 的质量/码率
- 编码环形缓冲深度上限 2 帧

### 输入

- `TOUCH` 定长 14 字节
- 坐标必须为**手表物理像素原始坐标**，手表侧不做任何变换
- 圆屏内接圆越界判定与 `MOVE` 滑出圆外的钳制规则
- `DOWN`/`UP` 的双侧超时兜底（手表 5 s 补发 `UP`；手机 10 s 合成 `CANCEL`）
- 上报节流 ≤ 30 Hz；`MOVE` 需 2 px 阈值
- 手机侧用原生 `MotionEvent` 注入而非合成 JS 事件
- `TOUCH` **不消耗** `VIDEO` Credit（方向独立）

### 渲染

- 设计基准 **192 dp**
- 安全区：方屏内缩 2.5%–5%，圆屏内缩 10%–14%
- 圆屏内接正方形硬极限 `(1 − 1/√2)/2 ≈ 14.64%`
- 所有边距使用百分比 / `vmin`，禁止绝对像素
- 屏幕形状经 `DISPLAY.safe_inset_permille` 上报，手机 **必须** 采信而非自行推测

### 承载

- 三种承载抽象：**BLE GATT（基线）**、**BLE L2CAP CoC**、**Wi-Fi TCP（可选）**
- GATT MTU 推荐目标 **247**（负载 244）
- 应用层 Credit 与 L2CAP 层 Credit 的双层关系

### 参考实现

- 手表端：LilyGO T-Display S3 AMOLED 1.43（ESP32-S3R8 / 8 MB PSRAM / 466×466 圆屏 / FT3168）
  - 双核分工：解码+刷屏绑 Core 1，BLE+协议栈绑 Core 0
  - PSRAM 双缓冲防撕裂（848 KB）
  - 解码后归还 Credit（而非收到后）
- 手机端：Kotlin SDK，`com.watchplay.sdk`
  - `UIRenderModule` / `CaptureModule` / `EncoderModule` / `TransportModule` / `TouchHandler` / `SessionManager`
- 工具：Python 协议参考实现（`tools/watchplay_proto.py`）与 trace 生成器

### 已知限制

| 限制 | 说明 | 影响 |
| :--- | :--- | :--- |
| ESP32-S3 不支持经典蓝牙 | 无 A2DP | 音频由手机本地播放 |
| ESP32-S3 H.264 只能软解 | `hardware_decode = 0`，`max_fps = 8` | `Negotiator` 因此不会选 Profile B |
| `CAPS` 总长上限 128 字节 | TLV 空间紧张 | 新增 TLV 需先做预算 |
| 捕获时间戳可选 | 端到端延迟只能靠 RTT 估计 | 可观测性不足 |
| 不支持多点触控 | 参考实现仅单点 | 复杂手势不可用 |
| `TOUCH` 无应用层重传 | 依赖双侧超时兜底 | UI 最多卡 10 s 后自愈 |
| Wi-Fi 承载未实现 | 仅定义 | 高码率场景受限 |

---

## 变更类型的判定（给维护者）

| 变更 | 版本影响 | 是否需改帧头 |
| :--- | :--- | :--- |
| 新增可选 TLV / opcode / 厂商扩展 | 无 | 否 |
| 新增必需 TLV | 次版本 +1 | 否 |
| 修改已有字段语义或长度 | **主版本 +1** | 可能 |
| 修改帧头布局 / CRC 算法 | **主版本 +1** | 是 |

> **在 1.0 之前，帧头布局冻结。** 见 [02-wire-format.md §9](02-wire-format.md)。任何想改帧头的需求 **必须** 被转化为 TLV 扩展，或推迟到 1.0 一并处理——因为一旦有第三方按 v0.1 实现了固件，改帧头就意味着所有已出货设备失联。
