# 01 — 传输层（Transport / Bearer）

> 状态：**Draft**（协议版本 `0.1`）。本文档定义 WatchPlay v0.1 的**承载层（bearer）**：帧如何进入 BLE GATT、BLE L2CAP CoC、Wi-Fi TCP 或经典蓝牙 RFCOMM，以及分片、重组、Credit 流控、发现与连接生命周期。关键词 `必须（MUST）`/`禁止（MUST NOT）`/`应当（SHOULD）`/`可以（MAY）` 的含义见 [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119)。字节级线格式见 [02-wire-format.md](02-wire-format.md)——**那是权威定义，本文不重复也不改写它**。

---

## 1. 范围与分层模型

### 1.1 分层

```
┌──────────────────────────────────────────────────────────────┐
│ 应用层：HTML+CSS 场景、编解码、触控映射                      │
│ 会话层 [03-session.md]  握手 / 能力协商 / 心跳 / 错误码      │
│ 视频层 [04-video.md]    帧头 / 分块 / Credit / 丢帧策略      │
├──────────────────────────────────────────────────────────────┤
│ ★ 线格式 [02-wire-format.md]  16 字节帧头 + 负载（承载无关） │
│ ★ 承载抽象（§1.2）  字节序列 · 最大传送单元 · 连接生命周期   │
├─────────────────┬───────────────────────┬────────────────────┤
│ Bearer A        │ Bearer B              │ Bearer C           │
│ BLE GATT        │ BLE L2CAP CoC         │ Wi-Fi (TCP / UDP)  │
│ 基线，处处可用  │ Android 10+ / IDF 可用│ Phase 2，可选      │
├─────────────────┴───────────────────────┴────────────────────┤
│ Bearer D：经典蓝牙 RFCOMM (SPP) — 仅有 BR/EDR 与双模设备     │
├──────────────────────────────────────────────────────────────┤
│ 链路层：BLE Link Layer (CRC24) / BR-EDR Baseband / Wi-Fi MAC │
└──────────────────────────────────────────────────────────────┘
```

**核心分层原则**：WatchPlay 的帧头层是**承载无关（bearer-independent）**的——它不假设一次被送达多少字节，也不假设底层是否保留消息边界。**连接发起方是接收端（手表）**（[03-session.md §1.2](03-session.md)）：接收端的"可被发现"状态（BLE 广播、经典蓝牙 inquiry scan、RFCOMM 监听）都是被动低功耗行为，因此承载层把"手表可被发现 → 手机连接 → 手表发 `HELLO`"当作默认时序，但 **必须（MUST）** 同时支持手机对已绑定设备主动发起重连（[§8.4](#84-地址与绑定)）。

### 1.2 承载抽象契约

一个承载要接入 WatchPlay，**必须（MUST）** 提供且仅提供以下三项能力。承载 **禁止（MUST NOT）** 参与帧类型解释、`seq` 语义、Credit 计算与能力协商；任何影响协议语义的承载特有逻辑 **必须（MUST）** 通过 [07-capabilities.md](07-capabilities.md) 的能力位暴露。

| 能力 | 含义 | 为什么必须 |
| :--- | :--- | :--- |
| **字节有序投递** | 一个方向上的字节序列，顺序不乱，重复可被容忍（§6.3） | 帧头与分块序号都依赖顺序 |
| **最大传送单元** | 一次可提交的字节数上限 `bearer_tu`（§6.1） | 决定分片粒度与 `max_chunk_bytes` 的合法上界 |
| **连接生命周期** | 连接建立 / 断开 / 断开原因（本地发起还是链路超时） | 会话层据此重连或重协商 |

### 1.3 四种承载实际提供的保证

| 承载 | 承载单位（`bearer_tu`） | 是否保留消息边界 | 承载级流控 | 适用设备 |
| :--- | :--- | :--- | :--- | :--- |
| **A: BLE GATT** | 20（默认 MTU 23）… 244（MTU 247） | **是**，每个通知/写操作保留边界 | 无（只有 §2.6 的拥塞事件） | 任何支持 BLE 的设备 |
| **B: BLE L2CAP CoC** | 512–1024（可更大） | **是**，每个 SDU 一个边界 | **有**，L2CAP Credit（§3.3） | 手机 Android 10+，手表控制器支持 CoC |
| **C: Wi-Fi TCP** | 1448（以太网 MTU 1500 减 IP/TCP 头） | **无**，纯字节流 | TCP 窗口（隐含） | 双方已在同一 IP 网络 |
| **D: 经典蓝牙 SPP** | 990（SDP `max_frame_size`，见 §5.2） | **无**，纯字节流 | RFCOMM credit + 协议栈缓冲 | 只有 BR/EDR，或双模设备 |

四者共同提供**可靠性与有序性**（GATT/CoC 靠 BLE 链路层重传，SPP 靠 BR/EDR 基带重传，TCP 靠自身重传）；**四者都不提供应用层 ACK**——真正的可靠性由 WatchPlay 的 `seq` 连续性检测 + Credit 方案提供（§7、[02-wire-format.md §5](02-wire-format.md#5-序列号)）。代价是丢帧不会被底层重传，只能被检测和上报。

---

## 2. Bearer A：BLE GATT（基线）

GATT 是唯一"处处可用"的承载：Android 任何版本、ESP32 任何 ESP-IDF 版本、iOS 未来接入都是同一套。**v0.1 一致性要求必须至少实现 Bearer A。**

### 2.1 UUID 分配

WatchPlay 使用一个**固定的 128 位基 UUID**，所有 WatchPlay UUID 由它派生：

```
基 UUID（WatchPlay Base UUID）：a7c00000-6cc1-4a51-8c1b-7b3f7ba7e0b2
派生规则：布局固定为 XXXXXXXX-6CC1-4A51-8C1B-7B3F7BA7E0B2；
         第 1 组 32 位 = 0xA7C0（高 16 位，固定）+ 功能码（低 16 位）；其余 96 位不变。
```

> **为什么不用 16 位短 UUID？** 16 位 UUID 空间由 Bluetooth SIG 统一分配。`0xA7C0` **不是** SIG 分配给 WatchPlay 的值，它只是**我们 128 位空间里的一个常量**。实现 **禁止（MUST NOT）** 在任何 AD 结构或 GATT 定义中使用 16 位形式，否则会与 SIG 未来分配的值冲突。该问题列入 [§11](#11-开放问题--open-questions) Q1。

| 名称 | 功能码 | 128 位 UUID | 方向 | 属性 |
| :--- | :--- | :--- | :--- | :--- |
| **WPS Service** | `A7C0` | `a7c0a7c0-6cc1-4a51-8c1b-7b3f7ba7e0b2` | — | Primary Service |
| **TX**（手机→手表） | `A701` | `a7c0a701-6cc1-4a51-8c1b-7b3f7ba7e0b2` | Source → Sink | Write Without Response（首选）+ Write（回退） |
| **RX**（手表→手机） | `A702` | `a7c0a702-6cc1-4a51-8c1b-7b3f7ba7e0b2` | Sink → Source | Notify |
| **CTRL**（控制） | `A703` | `a7c0a703-6cc1-4a51-8c1b-7b3f7ba7e0b2` | 双向 | Write + Notify（可选） |
| **PSM**（L2CAP 用） | `A704` | `a7c0a704-6cc1-4a51-8c1b-7b3f7ba7e0b2` | Sink → Source | Read + Notify（可选，见 §3.2） |
| **BT Service**（SDP 服务类） | `A705` | `a7c0a705-6cc1-4a51-8c1b-7b3f7ba7e0b2` | — | 只用于 Bearer D 的 SDP 记录，见 §5.1 |

`0xA706`–`0xA7FF` **保留**给后续标准扩展；厂商私有 UUID **禁止（MUST NOT）** 落在 `A7C0`–`A7FF` 区间内（那会与未来标准扩展冲突），应使用自有的 128 位基 UUID。

**为什么把 TX 和 RX 分成两个特征值**：ATT 的写与通知是不同属性；分开后手表端可以把 TX 的写回调直接投给协议解析器、把 RX 的通知句柄投给发送队列，无需在单次回调里判断方向。

### 2.2 MTU 协商

ATT 默认 MTU 为 **23 字节**，可用负载 = `MTU − 3`（3 字节 = 1 字节 Opcode + 2 字节 Attribute Handle），即 **20 字节**。

| 场景 | MTU | `bearer_tu` = MTU − 3 | `max_chunk_bytes` 上界 = MTU − 16 | 备注 |
| :--- | :--- | :--- | :--- | :--- |
| ATT 默认，未协商 | 23 | 20 | 7 | **低于 84 下限，协商必须失败**（03 §4.2 步骤 5） |
| **推荐目标** | **247** | **244** | **231** | 协议默认 `max_chunk_bytes = 244`，故 260 字节帧为两片（§6.1）；压成一片需降到 228（Q5） |
| Android 理论上限 | 517 | 514 | 501 | 可协商到，但不建议（见下） |
| Android 栈实际通知负载上限 | — | 多数机型 512 | — | 超过会被截断或静默失败 |

**为什么推荐 247 而不是 517**：

- 247 让 `max_chunk_bytes = 244` 的协议帧（16 字节帧头 + 244 字节负载 = 260 字节）分片后**只多一个 16 字节尾片**（§6.1），结构简单、可分片算术可预测。
- 517 会让每个 ACL 包跨 L2CAP 分片，提高 `ESP_GATTS_CONGEST_EVT` 触发频率；且部分 Android 机型 `requestMtu(517)` 返回 517 而实际通知被栈按 512 截断，产生"协议帧少 2 字节 → `hdr_crc` 连续失败 → resync 风暴"的故障模式，排查成本极高。

**协商流程（规范性）**：

1. 手机端完成服务发现后 **必须（MUST）** 调用 `requestMtu(247)` 并等待 `onMtuChanged`；**禁止（MUST NOT）** 重复调用（重复调用返回上次值、不触发协商）。
2. 返回 MTU 后，**承载层**上限为 `bearer_tu = MTU − 3`（去掉 3 字节 ATT 头）；**协议层**上限为 `max_chunk_bytes = min(本地, 手表, MTU − 16)`（[03-session.md §4.2](03-session.md) 步骤 5，16 = 帧头）。两者不相等是刻意的余量，实现 **禁止（MUST NOT）** 把 `max_chunk_bytes` 当成 `bearer_tu`（见 §6.1）。
3. MTU 247 时 `bearer_tu = 244`；协议默认 `max_chunk_bytes = 244`（[03-session.md §4](03-session.md)），因此 260 字节的默认协议帧在该承载单元下是**两片**（244 + 16，见 §6.1）。这是**允许的、功能正确的**，只是每帧多一次 BLE 提交；要压成一片需把 `max_chunk_bytes` 降到 `bearer_tu − 16`（MTU 247 时为 228），属于 [§11](#11-开放问题--open-questions) Q5 的效率优化，**不是** 合规要求。
4. 返回 `23 ≤ MTU < 247` 时 **必须（MUST）** 按 `max_chunk_bytes = min(244, MTU − 16)` 分块（`chunk_total = ceil(frame_bytes / (max_chunk_bytes − 20))`）并写入 `CAPS_ACK.max_chunk_bytes`。注意 MTU 23 时该上界为 7，低于 [03-session.md §4.2](03-session.md) 步骤 5 要求的下限 **84**（`20 + 64`）——此时手机 **必须（MUST）** 以 `RESULT.status = BANDWIDTH_INSUFFICIENT`（或 `RESOLUTION_UNSUPPORTED`）拒绝，**禁止（MUST NOT）** 静默继续按 244 分块。
5. 回调超时（**应当（SHOULD）** 2 s）时 **禁止（MUST NOT）** 假设 244 可用，必须按第 4 步降级；手表端 **禁止（MUST NOT）** 假设手机一定发起协商，在收到首个有效帧前 **应当（SHOULD）** 按最小分块保守工作。

> **失败模式**：手机忽略协商失败、继续按 244 分块。若接收端实现了 §6.3 的增量解析，画面仍能显示，只是每帧报 `chunk_total_mismatch`——"能跑但数据全错"。**一致性测试必须覆盖 MTU 23 场景**（§10 的 T-04/T-05）。

### 2.3 为什么 Write Without Response + Notification

| 选择 | 备选 | 理由 | 规避的失败模式 |
| :--- | :--- | :--- | :--- |
| TX 用 **Write Without Response** | Write With Response | 后者每个分块等一次 ATT 响应（典型 1 个连接间隔 ≈ 15 ms），37 分块的帧要 555 ms，10 fps 不可能 | 吞吐被 ATT 往返限制到 ~5 KB/s |
| TX **应当（SHOULD）** 同时声明 Write（有响应） | 只声明无响应 | 极少数栈在配对未完成时拒绝无响应写 | 配对窗口期完全无法推流 |
| RX 用 **Notify** | Indicate | Indicate 需 ATT 确认，且同一时刻只允许一个未确认 Indicate → `FEEDBACK`/`TOUCH` 被串行化，手机忙时直接卡住链路 | Credit 授予延迟，发送端饿死 |

因为没有 ATT 层 ACK，接收端 **必须（MUST）** 靠 `seq` 连续性（[02-wire-format.md §5](02-wire-format.md#5-序列号)）检测丢帧、靠 Credit 停止继续推数据（§7），而 **禁止（MUST NOT）** 依赖链路层保证交付。

### 2.4 连接参数

BLE 连接参数由 central（手机）决定，peripheral（手表）只能建议。Android 侧 **必须（MUST）** 在进入 `STREAMING` 前调用 `BluetoothGatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH)`，在进入 `DRAINING`/空闲后调用 `CONNECTION_PRIORITY_BALANCED`。

| 档位 | 连接间隔 | Slave Latency | 监督超时 | 用途 |
| :--- | :--- | :--- | :--- | :--- |
| **H-低延迟推流** | **15 ms**（12–15 允许） | **0** | 2000 ms | 10 fps MJPEG 流；每间隔至少一个传输机会 |
| **H-低延迟推流（保守）** | 30 ms | 0 | 4000 ms | 手机热/省电时；仍能满足 < 200 ms 端到端目标 |
| **IDLE 空闲** | 200 ms | 4 | 6000 ms | 会话建立后无数据、或仅心跳 |
| **SUSPEND 挂起** | 1000 ms | 4 | 8000 ms | 手表息屏但保持连接 |

约束（BLE Core 规范）：`supervision_timeout > (1 + slave_latency) × connection_interval × 2`。低延迟档 `(1+0) × 15 ms × 2 = 30 ms`，2 s 余量充足；空闲档 `(1+4) × 200 ms × 2 = 2000 ms`，6 s 留有 3 倍余量。

**取舍**：连接间隔从 30 ms 降到 15 ms，功耗约增加 60–80%（唤醒次数翻倍），但把单块的排队延迟从最多 30 ms 降到 15 ms。为满足 < 200 ms 端到端目标（[00-overview.md §2](00-overview.md#2-设计目标v01)），**推流期间应当（SHOULD）** 用 15 ms；空闲超过 5 s 后 **应当（SHOULD）** 切到 IDLE 档，否则手表电池会在"连着但没画面"时无谓消耗。

### 2.5 Android 实现要点

| 要点 | 具体要求 | 不做的后果 |
| :--- | :--- | :--- |
| `requestConnectionPriority` | 在 `onServicesDiscovered` 之后、首次写之前调用 `CONNECTION_PRIORITY_HIGH`（请求 11.25 ms，实际由链路协商落地） | 停留在默认 balanced（30–50 ms），推流卡顿 |
| **GATT 133** | 收到 `status=133`（`GATT_ERROR`）时 **必须（MUST）** `close()` 当前 `BluetoothGatt`，等待 `300 ms × 2^attempt + rand(0..100 ms)` 抖动后用 `connectGatt` 建新实例。同一地址连续 5 次 133 后 **应当（SHOULD）** 加入 30 s 冷却期并重新扫描 | 复用已损坏的 `BluetoothGatt` 实例 → 永久连不上 |
| 512 字节属性上限 | GATT 属性值最大 512 字节；**禁止（MUST NOT）** 设计任何依赖 >512 字节单次写/读的特性 | 写操作静默失败 |
| 通知静默丢弃 | Android BT 栈在回调密集或系统省电策略介入时会丢通知/丢回调，**不报错** | Credit 授予丢失 → 发送端永久饿死。对策见 §7.5 |
| 配对状态 | `BOND_NONE` 时 **应当（SHOULD）** 发起 `createBond()`，等 `BOND_BONDED` 后再进入 `NEGOTIATING` | 部分机型在未配对时限制服务发现 |
| 通知注册 | 写 CCCD（`0x2902`）**必须（MUST）** 等到 `onDescriptorWrite` 成功回调后才开始推流 | 首帧丢失 |

### 2.6 ESP32-S3（ESP-IDF）实现要点

- 服务用 `esp_ble_gatts_create_service` 注册：`a7c0a701` 属性 `ESP_GATT_CHAR_PROP_BIT_WRITE_NR | ESP_GATT_CHAR_PROP_BIT_WRITE`，`a7c0a702` 属性 `ESP_GATT_CHAR_PROP_BIT_NOTIFY`。
- 发送用 `esp_ble_gatts_send_indicate(..., need_confirm = false, ...)`。**必须（MUST）** 检查返回值：`ESP_FAIL` 表示内部控制块已满，此时 **禁止（MUST NOT）** 当作"已发送"而消耗 Credit——**应当（SHOULD）** 把该分块退回发送队列。
- **拥塞处理**：注册 `ESP_GATTS_CONGEST_EVT`；`congested = true` 时 **必须（MUST）** 立刻停止向该连接提交新通知，等解除后恢复。这正是"Credit 必须尊重拥塞"的含义：Credit 是**应用的发送许可**，拥塞是**链路的物理事实**，二者取与（AND），任一为假都不能推。
- 双核分工：GATT 回调（Core 0）**禁止（MUST NOT）** 做解码或 JPEG 解析，只做"收到字节 → 投进 ringbuf → `xTaskNotifyGive`"，解析在 Core 1 的解码任务侧。回调里做重活会直接把 BT 控制器的事件队列打满。协议层 **禁止（MUST NOT）** 依赖 BLE 5 扩展广播（§8.2 的预算按 31 字节传统广播计算）。

---

## 3. Bearer B：BLE L2CAP CoC

L2CAP Connection-oriented Channel 绕过 ATT/GATT，直接以 SDU 为单位传数据，**没有 ATT 的 3 字节头、没有每个通知的调度开销**。Android 侧要求 **API 29（Android 10）及以上**。

### 3.1 为什么 CoC 更快

| 对比项 | GATT | L2CAP CoC |
| :--- | :--- | :--- |
| 每单元协议开销 | 3 字节 ATT 头 + 4 字节 L2CAP 头 | **4 字节 L2CAP 头** |
| 单单元负载 | 244 字节（MTU 247） | 最多协商 MTU（参考 512–1024） |
| 提交粒度 | 每个通知一次栈调用 | 每个 SDU 一次栈调用，栈内部按 MPS 分片 |
| 发送队列 | 与 GATT 数据库、服务发现共享 | 独立信道，不与 GATT 抢 |
| 承载级流控 | 无 | **有**（L2CAP Credit） |

> 行业常见经验是 CoC 相比 GATT 能把有效吞吐提升约 1.5–3 倍。**这是估计值，不是本项目的实测数据**；本项目在给定假设下的推导见 [§3.6](#36-吞吐预期估计)。

### 3.2 建立流程

1. 手表端在启动时 **必须（MUST）** 调用 `esp_ble_l2cap_coc_create_server(ESP_BLE_L2CAP_CID_LE_COC, mtu, &cb)`，并用 `esp_ble_l2cap_coc_set_psm` 绑定一个**动态 PSM**。**禁止（MUST NOT）** 在 v0.1 中使用固定 PSM。
2. 手表端把 PSM 写入 `a7c0a704`（**PSM 特征值**，u16 小端）并通知手机。**可以（MAY）** 改为在 `CAPS`/`CAPS_ACK` 的 TLV 中携带，但两种方式 **禁止（MUST NOT）** 同时使用（会不一致）。
3. 手机端读取 PSM，调用 `BluetoothDevice.createL2capChannel(psm)`，得到 `BluetoothSocket`（`BluetoothDevice.createInsecureL2capChannel` 用于无配对场景，见 §3.5）。
4. 建立成功后，**必须（MUST）** 保持 GATT 连接不拆（作为回退与心跳通道），但视频与 `FEEDBACK` 数据流切到 CoC。
5. 会话层 `CONTROL(SWITCH_BEARER)` 语义见 [03-session.md](03-session.md)；**两种承载上的 WatchPlay 帧格式完全相同**，切换承载不改变帧、不重置 `seq`（**应当（SHOULD）** 保持单调，避免接收端误判丢帧）。

### 3.3 两层 Credit 的交互——**这是本承载最容易搞错的地方**

| 层级 | 单位 | 授予方 | 授予消息 | 语义 |
| :--- | :--- | :--- | :--- | :--- |
| **L2CAP 层** | 1 个 **SDU** | 接收端 | `LE Flow Control Credit` PDU（L2CAP 信令，不由应用构造） | 接收端缓冲还能吃下几个 SDU |
| **WatchPlay 应用层** | 1 个 **协议帧**（即 1 个视频分块） | 接收端 | `FEEDBACK` 帧（`type = 0x09`） | 接收端解码/显示流水线还能吃下几个分块 |

**发送一个协议帧 = 提交一个 SDU**（前提：该帧未被承载层再分片，即 `16 + payload_len ≤ bearer_tu`）。两层必须同时满足：可以发送 ⟺ (WatchPlay 剩余 Credit ≥ 1) **AND** (L2CAP 剩余 Credit ≥ 1) **AND** (not congested)。

**失败模式（必须避免）**：把两层 Credit 当成同一个计数器。典型症状是 L2CAP 层用尽而 WatchPlay 层看起来还有很多 Credit，发送端持续 `write()`，`BluetoothSocket` 输出流阻塞，蓝牙线程卡死在 `write` 上 → 心跳、`TOUCH` 回传全部停摆 → 手表端因监督超时断连。

| 规范要求 | 说明 |
| :--- | :--- |
| L2CAP 初始 Credit | **应当（SHOULD）** ≥ `ceil(初始窗口 / 每 SDU 可容纳的分块数)`。例：`initial_credits = 64`、MTU 1024、MPS 251（每 SDU 占 `ceil(1024/251) = 5` 个 L2CAP PDU）→ `ceil(64/5) = 13`，**应当（SHOULD）** 取 ≥ 13；实践上直接给 32 以上以吸收突发。MTU 512（`ceil(512/251) = 3`）时 **应当（SHOULD）** ≥ `ceil(64/3) = 22` |
| 线程模型 | `BluetoothSocket` 输出流 **必须（MUST）** 在独立线程写入，**禁止（MUST NOT）** 在 BT 回调线程或 UI 线程写 |
| 监控与降级 | L2CAP Credit 消耗速度 **必须（MUST）** 被监控；出现"`write` 返回但 Credit 长期为 0"（Android 不暴露该计数，可用 `write` 阻塞时长间接判断）时 **应当（SHOULD）** 降级回 GATT |

### 3.4 SDU / MTU 建议

| 参数 | 建议值 | 理由 |
| :--- | :--- | :--- |
| 协商 MTU（手机侧请求） | **1024** | 足以容纳 4 个 244 字节协议帧/SDU |
| 协商 MTU（手表端上限 / 最小可用） | **512** | 参考实现的上限 512（ESP-IDF 的 CoC RX 缓冲在 internal RAM）；低于 512 时 CoC 相对 GATT 无优势 |
| MPS | 由栈决定，参考值 251 | 由控制器 ACL 包长决定，应用不可控 |

**当 `bearer_tu = 512` 时**：一个 260 字节的协议帧直接放进一个 SDU，不需要 §6 的承载级再分片——这是 CoC 最舒服的形态。

> **风险**：若为了更大 MTU 打开 `CONFIG_SPIRAM_USE_MALLOC` 让 CoC 缓冲落到 PSRAM，会引入 PSRAM 访问延迟，在 10 fps 下可能与 Core 1 的 JPEG 解码抢带宽。**v0.1 建议维持 512，把 CoC 的收益定位在"减少协议开销"而非"提高单包尺寸"**（见 [§11](#11-开放问题--open-questions) Q8）。

### 3.5 实际坑点

| 坑点 | 现象 | 要求 |
| :--- | :--- | :--- |
| 需要配对/绑定 | `createL2capChannel` 抛 `IOException`，或返回的 socket 立即 EOF | **必须（MUST）** 在 GATT 阶段完成绑定后再建 CoC；失败时 **必须（MUST）** 回退 GATT，**禁止（MUST NOT）** 直接退出 |
| PSM 必须动态 | 固定 PSM 可能与系统服务冲突 | 见 §3.2 步骤 1 |
| Android ROM 差异 | 部分 ROM 的 CoC 实现有 bug（连上但无数据） | **应当（SHOULD）** 建链后 2 s 内做一次应用层 `PING/PONG` 探活，无响应则回退 |
| 无配对时的加密 | `createInsecureL2capChannel` 明文传输 | **可以（MAY）** 用于开发；生产 **应当（SHOULD）** 用安全信道 |

### 3.6 吞吐预期（估计）

**以下为在给定假设下推导的期望值，不是实测结果。** 假设：连接间隔 15 ms、每个连接事件 5 个 ACL 包、无重传、无应用层丢包；有效负载按 BLE 数据长度扩展后的 251 字节形态计。

| 承载 | 每包有效负载 | 理论字节率 | 计入 ATT/GATT 开销后的期望 |
| :--- | :--- | :--- | :--- |
| GATT，MTU 23 | 20 | 6.7 KB/s | ~6.7 KB/s |
| GATT，MTU 247 | 244 | 81 KB/s | **~60–80 KB/s（期望）** |
| CoC，MTU 1024 / MPS 251 | 247（4 PDU/SDU） | 82 KB/s | **~75–82 KB/s（期望）** |

结论：**在只有 BLE 的 MCU 上（参考实现即此类），CoC 的收益主要来自少一层 ATT 头和更少的栈调度，而不是量级提升**；它更重要的价值是给 H.264（[04-video.md](04-video.md) Profile B）留出更大的 SDU 空间。真正的量级提升要靠 Bearer C。

---

## 4. Bearer C：Wi-Fi（可选，Phase 2）

Wi-Fi 承载 **不是 v0.1 的一致性要求**，实现者 **可以（MAY）** 跳过。它的存在是为了 H.264 高码率档和未来的富交互场景。

### 4.1 拓扑与发现

| 方式 | 说明 | 前置条件 |
| :--- | :--- | :--- |
| **Wi-Fi Direct（P2P）** | 手机做 GO，手表做 client | Android 13+ 需 `NEARBY_WIFI_DEVICES`；Android < 13 需定位权限 |
| **SoftAP** | 手表开 AP，手机连入 | 手表需持久化 SSID/密码策略；功耗高 |
| **同 LAN** | 双方已在同一网段 | 需 mDNS 发现或手动配对码 |

**发现方式（规范性）**：

- **mDNS**：手表发布 `_watchplay._tcp.local`，TXT 记录 **必须（MUST）** 含 `v=1`、`shape=round|rect`、`w=466`、`h=466`、`codec=mjpeg,h264`。手机侧 **应当（SHOULD）** 在 5 s 未发现服务时回退。
- **BLE bootstrap 交接（推荐）**：先建立 Bearer A/B，通过 `CONTROL` 帧（见 [03-session.md](03-session.md)）交换 Wi-Fi 凭据（SSID、口令或 P2P 协商信息），再切换承载。凭据 TLV 编号见 [07-capabilities.md](07-capabilities.md)；**必须（MUST）** 在已绑定的加密链路上传输口令，否则 **禁止（MUST NOT）** 支持该交接。

### 4.2 TCP 的帧边界问题

TCP **不保留消息边界**。接收端 **必须（MUST）** 常驻运行 [02-wire-format.md §7](02-wire-format.md#7-解析伪代码) 的增量解析器（累积字节 → 找 `0x57 0x50` → 校验 `hdr_len`/`hdr_crc` → 等 `hdr_len + payload_len` 到齐 → 交付）。

- **禁止（MUST NOT）** 假设"一次 `read()` 等于一帧"，也 **禁止（MUST NOT）** 发明一个**必需**的长度前缀——那会与 BLE 承载的字节序列不兼容，破坏承载无关性。
- **可以（MAY）** 在 Wi-Fi 承载上用长度前缀作为**可选优化**，但接收端 **必须（MUST）** 同时支持无前缀的裸流路径，否则不算 Bearer C 兼容实现。
- 重同步完全复用 [02-wire-format.md §7.1](02-wire-format.md#71-重新同步resync)，**禁止（MUST NOT）** 在本文档重新定义。

### 4.3 TCP_NODELAY 与队头阻塞

- **必须（MUST）** 设置 `TCP_NODELAY`：Nagle 会把小的 `TOUCH`/`FEEDBACK` 帧攒到 40 ms 才发，直接污染 < 200 ms 的延迟预算；而视频帧是大块写入，Nagle 对它没有收益。
- **队头阻塞（HOL）**：TCP 的重传是字节流级有序重传。丢一个段，后续已到达的段（包括**下一个视频帧**）都要在接收缓冲里等；对 10 fps 视频会把"丢 1 个包"放大成"卡 1–3 帧"，`FEEDBACK` 与 `TOUCH` 也被挡住。
- **建议**：实时视频的正解是 **UDP + 小的应用层 ARQ**（只重传关键帧或当前显示帧，超时丢整帧）。v0.1 **不定义** UDP 承载，因为它需要一个承载无关的"帧有效性"判定点，属于 [04-video.md](04-video.md) 的丢帧策略。**若实现者选择 UDP，必须（MUST）** 自行维护分块序号连续性检测与超时丢帧，并 **必须（MUST）** 在能力位中声明该行为非标。

---

## 5. Bearer D：经典蓝牙（RFCOMM / SPP）

**给"只有 BR/EDR、没有可用 BLE"的手表准备的承载。** 可选承载，实现者 **可以（MAY）** 跳过；它解决接入面，不解决带宽——要带宽看 Bearer B/C。

BLE 在 4.0 之后才普及。一批在售方案（老 RTOS 平台、BLE 未通过认证的芯片、把 BLE 从射频到天线都省掉的低成本方案）只提供经典蓝牙的 SPP。协议若只认 BLE，这批设备要么改硬件，要么无法接入。

> **音频不走这条链路。** A2DP 是单向、有损、面向连续播放的，**不能（MUST NOT）** 承载 WatchPlay 帧；音频旁路见 [03-session.md §3.7](03-session.md) 的 `AUDIO` TLV。双模设备 **可以（MAY）** 同时开 SPP（数据）与 A2DP（音频）两条独立链路。

### 5.1 服务注册与发现

| 项 | 要求 |
| :--- | :--- |
| 角色 | 手表是 RFCOMM **server**（监听），手机是 **client**（连接）。会话层不变：`HELLO` 仍由手表先发（[03-session.md §1.2](03-session.md)） |
| 服务类 UUID | **必须（MUST）** 在 SDP 记录的 `ServiceClassIDList` 中放入 `a7c0a705-6cc1-4a51-8c1b-7b3f7ba7e0b2`（功能码 `A705`，[§2.1](#21-uuid-分配)） |
| 协议描述 | **必须（MUST）** 提供 `ProtocolDescriptorList` = [L2CAP, RFCOMM, channel]，channel 由栈分配 |
| 唯一性 | **禁止（MUST NOT）** 只注册 SIG 的 SPP UUID `00001101-0000-1000-8000-00805F9B34FB` 作唯一标识——任何串口外设都会命中 |
| 发现 | 手机 **必须（MUST）** 能仅凭 SDP 查询 `a7c0a705` 判定"这是 WatchPlay 设备"。**禁止（MUST NOT）** 要求该设备先被 BLE 扫描到 |

> 双模设备 **可以（MAY）** 同时用 BLE 广播做预筛，但发现流程 **禁止（MUST NOT）** 依赖 BLE——单模 BR/EDR 设备没有广播信道，依赖它就等于取消本承载。

### 5.2 `bearer_tu` 与上报

RFCOMM 的单帧上限是协商出的 N1（默认 127 字节）。手表 **应当（SHOULD）** 在 SDP 的 `max_frame_size` 中给出 **990**，双方按 `bearer_tu = min(SDP 值, 本地缓冲)` 工作；实现 **禁止（MUST NOT）** 假设对端至少给到 128。

`TRANSPORT.mtu` 是 u8 且 **上限截断为 255**（[07-capabilities.md §2.2](07-capabilities.md)），990 装不进去：

| 规则 | 要求 |
| :--- | :--- |
| `TRANSPORT.mtu` | **必须（MUST）** 填 `255` |
| 真实 `bearer_tu` | **必须（MUST）** 由 `TRANSPORT.ext` 的前 2 字节（u16 小端）携带，仅当 `bearers` 的 bit3 置位时存在 |
| 手机侧 | **禁止（MUST NOT）** 把 `mtu = 255` 当 `bearer_tu` 用——那会把每块从 974 字节压到 239 字节，分块数翻四倍 |
| `max_chunk_bytes` | 上界为 `bearer_tu − 16`；默认仍 244，双方取小。SPP 上提高它 **可以（MAY）** 显著减少每块的 20 字节子头开销 |

### 5.3 帧边界

RFCOMM 是**字节流**，不保留消息边界（与 TCP 同构，与 GATT/CoC 相反）：一次 `read` 可能返回半个协议帧、一个半帧或三个帧。

- 接收端 **必须（MUST）** 使用 [02-wire-format.md §7](02-wire-format.md#7-解析伪代码) 的增量解析器；
- **禁止（MUST NOT）** "一次读 = 一帧"，**禁止（MUST NOT）** 在流上加自定义长度前缀；
- 重组缓冲上限与 `resync` 规则与 TCP 相同（[§6.4](#64-缓冲上限与背压)）。

### 5.4 两层流控

RFCOMM 自带基于 credit 的帧级流控。它与 WatchPlay 应用层 Credit 是**两层**，关系与 [§3.3](#33-两层-credit-的交互这是本承载最容易搞错的地方) 的 L2CAP 情形同构：

| 层级 | 单位 | 授予方 | 语义 |
| :--- | :--- | :--- | :--- |
| RFCOMM 层 | 1 个 RFCOMM 帧 | 接收端协议栈 | 对端还能吃下几个帧 |
| WatchPlay 层 | 1 个协议帧（1 个视频分块） | 接收端应用 | 解码/显示流水线还能吃下几个分块 |

可以发送 ⟺（应用层 Credit ≥ 1）**且**（RFCOMM 有 credit）**且**（not congested）。应用层 Credit 是唯一权威：RFCOMM 有 credit 不代表可以超发。

| 规范要求 | 说明 |
| :--- | :--- |
| 写线程 | `BluetoothSocket` 输出流 **必须（MUST）** 在独立线程写，**禁止（MUST NOT）** 在 BT 回调线程或 UI 线程写 |
| 阻塞写 | `write()` 会阻塞；**必须（MUST）** 设超时，连续超时 **应当（SHOULD）** 2 s 后按 [§9.3](#93-退避重试) 退避 |
| 降级 | RFCOMM credit 长期为 0 而应用层窗口不变时，双模设备 **应当（SHOULD）** 降级到 Bearer A |

### 5.5 连接生命周期与配对

| 场景 | 要求 |
| :--- | :--- |
| 配对 | 建 RFCOMM 前 **必须（MUST）** 完成 SSP 绑定；`BOND_NONE` 时手机 **应当（SHOULD）** 发起 `createBond()` |
| 可发现性 | 未绑定 **可以（MAY）** 保持 inquiry scan；绑定后 **应当（SHOULD）** 关闭 inquiry scan，只对已绑定设备监听 RFCOMM |
| 断连判定 | SPP 没有 BLE 的监督超时可依；**必须（MUST）** 同时依靠应用层心跳（[03-session.md §6](03-session.md)）与读线程 EOF |
| 半帧 | 读线程 EOF 或链路出错时 **必须（MUST）** 丢弃未完成帧，规则同 [§9.4](#94-断连与半帧) |
| 重连 | 退避与 [§9.3](#93-退避重试) 相同；**禁止（MUST NOT）** 以"SPP 一条链路只允许一个连接"为由跳过退避 |

### 5.6 吞吐（未实测）

SPP 每帧可到 990 字节，且不受 BLE 连接事件窗口限制，同等条件下吞吐通常高于 GATT。具体值取决于射频共存（Wi-Fi / BR-EDR 共用前端）、手机栈与 RF 环境。**本项目未实测，因此不给数字**——需要时按 [§3.6](#36-吞吐预期估计) 的方法自测。

### 5.7 与其他承载的关系

- `bearers` 的 **bit3** 置位（[07-capabilities.md §2.2](07-capabilities.md)）；
- 双模设备 **可以（MAY）** 在 GATT 与 SPP 之间用 `CONTROL(SWITCH_TRANSPORT)` 切换，语义见 [§9.4](#94-断连与半帧) 的承载切换行：不视为断连，不重置 `seq`；
- 只有 SPP 的设备 `preferred = 0x08`，没有回退承载，断连即按 §9.3 重连。

---

## 6. 协议帧在承载上的分片与重组

### 6.1 术语与算术

| 符号 | 含义 | 默认值 |
| :--- | :--- | :--- |
| `bearer_tu` | 承载一次提交能携带的字节数 | GATT MTU 247 → **244** |
| `max_chunk_bytes` | **协议帧的 `payload_len` 上限**（协商值，见 [02-wire-format.md §6](02-wire-format.md#6-负载长度上限)） | **244** |
| 最大协议帧 | `16 + max_chunk_bytes` | **260** 字节 |

**`bearer_tu` 与 `max_chunk_bytes` 分属两层**：前者是承载一次提交的字节上限（`MTU − 3`），后者是协议 `payload_len` 上限（协商值，[03-session.md §4.2](03-session.md) 步骤 5 的上界为 `MTU − 16`）。二者的 **13 字节差额** 是刻意的：3 字节给 ATT 头，其余 10 字节给未来 `hdr_len > 16` 的帧头扩展。**必须（MUST）** 在 `CAPS_ACK.RESULT` 中记录协商后的 `max_chunk_bytes`。分片算术：

| MTU | `bearer_tu` | `max_chunk_bytes` | 260 字节协议帧需几次提交 | 每片字节 | 每片承载开销 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 23 | 20 | 7（低于 84 下限 → 协商失败） | — | — | — |
| 247（协议默认） | **244** | **244** | **2** | **244 + 16** | **1.2%** |
| 247（推荐优化） | 244 | 228 | 1 | 244 | 1.2% |
| 517 | 514 | 244 | 1 | 260 | 0.6% |
| CoC 512 | 512 | 244 | 1 | 260 | 0.8% |
| SPP 990（本协议默认 `max_chunk_bytes`） | 990 | 244 | 1 | 260 | 0.4% |
| SPP 990（把 `max_chunk_bytes` 提到 974） | 990 | 974 | 1 | 990 | 0.2% |

> **注意 247 下的第二片只有 16 字节**：允许，但每帧多一次 BLE 提交。若希望避免，**可以（MAY）** 把 `max_chunk_bytes` 降到 **228**（16 + 228 = 244，正好一片），代价是分块数 +7%。两种取值都合规，v0.1 **不要求** 改（见 [§11](#11-开放问题--open-questions) Q5）。

### 6.2 分片规则

对一个长度为 `L = 16 + payload_len` 的协议帧：

1. 发送端 **必须（MUST）** 按顺序把帧切成 `ceil(L / bearer_tu)` 片，每片 `bearer_tu` 字节，最后一片为余数；对 GATT，每片 **必须（MUST）** 是一次独立的写操作。
2. **禁止（MUST NOT）** 把两个不同协议帧的字节混进同一次提交。接收端虽能靠 magic 重同步，但混装会让"丢一片 → 丢一帧"扩大成"两帧都丢"，并让 `seq` 连续性统计失去意义。
3. 同一帧的所有片 **必须（MUST）** 在 Credit 允许下连续提交；**禁止（MUST NOT）** 把半帧留在队列里先发下一帧——分块顺序由视频头的 `chunk_index` 保证，交错会让重组缓冲需求翻倍。

### 6.3 重组规则（**关键设计决定**）

**决定：任何承载上都不需要承载级长度前缀。** 接收端只是一个字节累加器 + 增量解析器：`GATT onCharacteristicChanged(data)` / `CoC socket.read(buf)` / `TCP socket.read(buf)` / `RFCOMM InputStream.read(buf)` → 追加到 ringbuf → 运行 [02-wire-format.md §7](02-wire-format.md#7-解析伪代码) 的解析循环。

之所以成立，是因为帧头是**自描述的**：`magic`（偏移 0–1）给出可搜索的同步点；`hdr_crc`（偏移 14–15）在只收到 16 字节时就能验证，**无需等待负载**；`hdr_len` + `payload_len` 精确给出帧结束位置。因此接收端**永不需要**外部告知"这一段是不是一个完整帧"。**必须（MUST）** 复用 [02-wire-format.md §7](02-wire-format.md#7-解析伪代码) 的解析流程，**禁止（MUST NOT）** 在本文档重新定义。

对 TCP 的结论 **不同但兼容**：TCP 无消息边界，所以 **必须（MUST）** 让同一增量解析器常驻运行，而不是"每读一次当一帧"。由于解析器本来就是流式的，这一差异 **不产生** 额外协议机制。解析器 **必须（MUST）** 是可重入的字节流驱动形式 `feed(bytes) -> [帧]`，只保留"尚未凑齐的尾部字节"；**禁止（MUST NOT）** 保留"已解析但未交付"的帧队列（§6.4 的背压除外）。

### 6.4 缓冲上限与背压

| 缓冲 | 上限 | 依据 / 超限动作 |
| :--- | :--- | :--- |
| 帧头层重组缓冲 | **`2 × (16 + max_chunk_bytes)` = 520 字节**（默认值） | [02-wire-format.md §7.2](02-wire-format.md#72-背压)：`2 × 单帧最大字节数`；超限即判流已错位——丢弃缓冲、`resyncCount += 1`，并按 §7.1 的 5 秒 32 次阈值决定是否 `BYE(REASON_PROTOCOL_ERROR)` |
| 视频层整帧重组缓冲 | `2 × max_frame_bytes`（约 2 MB，见 [00-overview.md §2](00-overview.md#2-设计目标v01)） | 由 [04-video.md](04-video.md) 定义，**不是**承载层职责 |
| 出向分片队列 | **`≤ 2 × max_chunk_bytes` 个待发分片** | 超过说明 Credit 计算有误；**应当（SHOULD）** 记 `txQueueOverflow` 并丢**整帧** |

应用层消费不过来时 **必须（MUST）** 停止从 socket/回调取数据（停止 `read`、不再注册下一轮回调），**禁止（MUST NOT）** 无界缓冲。

---

## 7. Credit 流控

`FEEDBACK` 帧（`type = 0x09`，**22 字节定长负载**）的字段布局由 [04-video.md §4.2](04-video.md) 定义；本文档只规范**传输语义**。

### 7.1 语义

- **1 个 Credit = 发送端可以发送 1 个协议帧**（对视频即 1 个分块）；Credit 是**接收端授予发送端**的单向许可，反方向由对端独立授予。发送端 **禁止（MUST NOT）** 在 Credit 为 0 时发送任何视频帧。
- **初始窗口：64 个 Credit**（`W0 = 64`），**必须（MUST）** 与 `CAPS_ACK.RESULT.initial_credits` 一致——该字段是初始 Credit 的**唯一权威来源**（见 [03-session.md §4](03-session.md), 默认 `64`，协商范围 `clamp(free_queue_slots, 16, 128)`）；[04-video.md §4.1](04-video.md) 给出同一默认值。由接收端在会话进入 `STREAMING` 时（`CAPS_ACK` 之后）立即授予。
- **禁止（MUST NOT）** 使用隐式初始窗口（"没收到 `FEEDBACK` 就假定有 64"）。0 是唯一的合法初值，显式授予是唯一途径；若接收端尚未准备好，**必须（MUST）** 授予 0 并随后用 `FEEDBACK` 补足。
- **`FEEDBACK.credits` 是绝对值**（u32，`FEEDBACK` 偏移 0）：它表示**接收端当前愿意接收的剩余分块数**，**不是**增量。这一点由 [04-video.md §4.2](04-video.md) 定义，本文档 §7.5 说明为什么绝对语义是通知可能丢失时的唯一安全选择。

### 7.2 授予规则

| 参数 | 建议默认值 | 理由 |
| :--- | :--- | :--- |
| 初始窗口 `W0` | **64** 分块（= `CAPS_ACK.RESULT.initial_credits`） | 约 14 KB 媒体数据，覆盖接收端一次解码 + 一次显示；太小会每帧多次停顿，太大则接收端缓冲压力大 |
| 补充阈值 `T_low` | 剩余 **< `W0/4` = 16** 时触发补授 | 与 [04-video.md §4.4](04-video.md) 的"降到初始值 1/4 以下时催促"一致；16 个分块 ≈ 3.5 KB，足以覆盖一次 BLE 往返（30–60 ms）内的消耗 |
| 补授目标 / 最小补授量 | **补回到 `W0`（64）**；授予量 < 16 则不发，等累积 | 固定窗口让接收端缓冲占用可预测（**禁止** 无限增长）；避免每块都发 20 字节 `FEEDBACK` 吃光上行带宽 |
| 常规节流点 | 每成功解码 **一帧** 发一次 `FEEDBACK` | [04-video.md §4.4](04-video.md) 的默认节流点；**禁止（MUST NOT）** 每收到一个分块就发一次 |
| 周期性重发 | Credit 为 0 时每 **500 ms**；无数据时每 **2 s** | 对抗通知静默丢弃（§7.5），并保活、让发送端知道窗口仍开着 |

### 7.3 Credit 耗尽时的行为

发送端 Credit 为 0 时：

1. **必须（MUST）** 停止发送视频帧（`type = 0x05`），**禁止（MUST NOT）** "丢弃并继续"（drop-and-continue）——不得无视 Credit 继续发下一帧。
2. **可以（MAY）** 按 [04-video.md](04-video.md) 的丢帧策略**丢弃整帧**（例如队列积压 3 帧时丢弃最旧的 2 帧），但丢弃本身 **禁止（MUST NOT）** 消耗 Credit，也 **禁止（MUST NOT）** 造成接收端状态误判。被丢弃帧之后的下一帧 **必须（MUST）** 置 `DISCONTINUITY(0x0040)`。
3. **可以（MAY）** 继续发送非视频帧（`PING`/`PONG`/`CONTROL`/`BYE`），并 **应当（SHOULD）** 用这些帧而非视频帧探测链路。
4. 接收端 Credit 仍未归还超过 `drain_timeout_ms`（默认 **2000 ms**，[03-session.md §4.1](03-session.md)）时，会话进入 `DRAINING`（**背压状态，不是断连**）：停止 `VIDEO`，保持心跳与 `FEEDBACK` 往返；持续超限则由 [04-video.md §4](04-video.md) 的码率自适应降档，而 **禁止（MUST NOT）** 因背压主动断链。若此时 `PING` 连续 3 次无 `PONG`，按 §9.2 判死。

### 7.4 与 L2CAP Credit 的交互

见 [§3.3](#33-两层-credit-的交互这是本承载最容易搞错的地方)。摘要：

| 情形 | 正确行为 |
| :--- | :--- |
| WatchPlay Credit = 0，L2CAP Credit > 0 | 停止发送，靠 §7.2 的 500 ms 重发周期催 |
| WatchPlay Credit > 0，L2CAP Credit = 0 | **必须（MUST）** 视为"不可发送"，**禁止（MUST NOT）** 阻塞在 `write()` 上；**应当（SHOULD）** 把分片退回队列并让出线程 |
| 两者都 > 0，但 GATT 拥塞（`ESP_GATTS_CONGEST_EVT`） | **必须（MUST）** 停止推进，等拥塞解除；**禁止（MUST NOT）** 消耗 Credit（该分片未真正离开发送端） |

### 7.5 通知丢失与幂等性

Android 与 ESP32 都可能静默丢弃通知/写操作（§2.5、§2.6）。因此：

- `FEEDBACK` 中的 Credit 值 **必须（MUST）** 是**接收端当前愿意接收的剩余分块数（绝对值）**，而不是增量（[04-video.md §4.2](04-video.md)：`credits` = "当前可用 Credit 总数（不是增量）"）。绝对语义幂等：丢一个 `FEEDBACK` 只短暂降低吞吐，不会永久累积偏差；增量式的"授予 +64"一旦丢失就永久少 64 个 Credit，发送端会静默饿死。
- 接收端在 Credit 消耗到阈值以下、或距上次 `FEEDBACK` 超过 500 ms 时，**应当（SHOULD）** 无条件重发一次当前绝对窗口。
- **`max_chunk_bytes`、`initial_credits` 或连接参数变更时，双方 Credit 窗口必须（MUST）重置** 并由接收端重新显式授予，旧授予立即失效；连接断开时 **必须（MUST）** 丢弃全部 Credit 状态，重连后重新走协商并从 0 重新授予（[03-session.md §8](03-session.md)：重连 **禁止（MUST NOT）** 复用上次会话参数）。
- 归还时机：Credit **必须（MUST）** 在 Core 1 **成功解码完成后**归还，而不是 Core 0 收到分块时就归还（[04-video.md §4](04-video.md)）。否则手机可以持续满速推送，而手表解码跟不上时静默丢帧且手机毫无感知——Credit 机制就失去了意义。

> **绝对值语义是本文档引入的（见 [§11](#11-开放问题--open-questions) Q4）**：若 [04-video.md](04-video.md) 定义了"增量"字段，实现者 **必须（MUST）** 遵循 04；本文档的幂等性要求可由"500 ms 周期性重发当前值"满足，两者不冲突。

### 7.6 示例：一帧的 Credit 时间线

场景：`max_chunk_bytes = 244`，`chunk_total = 37`（[02-wire-format.md §8](02-wire-format.md#8-完整示例一个-mjpeg-帧的字节序列) 的 8123 字节 JPEG），`initial_credits = 64`，`T_low = W0/4 = 16`，补授目标 `W0 = 64`。**假定无丢包。**

**主场景：整帧装得进一个初始窗口。** 37 < 64，因此本帧**全程不需要补授**，窗口从 64 线性降到 27：

| # | 事件 | 发送端剩余 Credit | 在发第几块 | 接收端动作 |
| :-- | :--- | :--- | :--- | :--- |
| 1 | 进入 `STREAMING` → 收到 `FEEDBACK` | 0 → **64** | — | 发 `FEEDBACK(credits=64)` |
| 2 | 发送 chunk 0 | 63 | 0 | — |
| 3 | …连续发送… | 28 | 35 | 剩余 28 > `T_low`(16)，不补授 |
| 4 | 发送 chunk 36（帧结束） | **27** | 36 | 解码完成后回 `FEEDBACK(credits=64)`（[04-video.md §4.4](04-video.md)：每解码完一帧一次） |
| 5 | 收到该 `FEEDBACK` | **64** | — | 下一帧继续。**每一帧边界窗口都回到 64**，1/4 催促阈值在稳态下永远不会被触发 |

**边界场景：窗口装不下整帧时**（例如 `initial_credits` 被协商成 32，或帧比 37 块更大）。设窗口 32、帧 40 块——`T_low = 8`，窗口会在帧中间跨过阈值，必须补授：

| # | 事件 | 发送端剩余 Credit | 在发第几块 | 接收端动作 |
| :-- | :--- | :--- | :--- | :--- |
| 1 | 收到 `FEEDBACK` | 32 | — | — |
| 2 | 发送 chunk 0–23 | 32 → **8** | 23 | 8 不小于阈值 8，不补授 |
| 3 | 发送 chunk 24 | **7 < 8** | 24 | 触发补授 → 回 `FEEDBACK(credits=32)` |
| 4 | 收到 `FEEDBACK` | 32 | — | 此刻 chunk 25 可能已在途，见下方说明 |
| 5 | 发送 chunk 25–39 | 17 | 39（帧结束） | 剩余 17 ≥ 8，不再补授 |
| 6 | 若第 3 行的 `FEEDBACK` **被静默丢弃** | 7 → 0（再发 7 块后饿死） | 停在 chunk 31 | 500 ms 后因"上次 `FEEDBACK` 超时"重发 `FEEDBACK(credits=32)`，发送端补齐剩余 8 块；本帧只多 500 ms 延迟，**不会永久卡死**——这正是绝对值 + 周期重发的价值 |

> **在途问题（第 4 行）**：绝对窗口必须是"接收端此刻还能接收多少块"。**应当（SHOULD）** 采用"窗口 = `W0` −（已收到但未被应用层消费的块数）"，而**不是**"`W0` −（未收到的块数）"——后者会把在途块当成还没发出的块，导致窗口虚高。

---

## 8. 发现、广播与绑定

### 8.1 设备名约定

格式 `WP-<model>-<addr4>`，示例 `WP-TDS3AM-1a2b`：

- `model`：ASCII `[A-Z0-9]`，**必须（MUST）** ≤ 8 字符，例如 `TDS3AM`（T-Display S3 AMOLED）。
- `addr4`：蓝牙地址最低 16 位的 4 位十六进制小写，用于同型号多设备区分。
- 完整名称长度 **必须（MUST）** ≤ 16 字节，确保能与 128 位服务 UUID 一起放进广播（见 §8.2 的预算）。

### 8.2 广播载荷布局

**必须（MUST）** 在广播中包含 Flags、128 位完整服务 UUID 与 Manufacturer Specific Data；设备名与其余 MSD 字段 **应当（SHOULD）** 放进扫描响应（Scan Response）以节省广播空间。

| AD 结构 | 长度字节 | 内容 | 必需 |
| :--- | :--- | :--- | :--- |
| Flags | 2 | `0x06`（LE General Discoverable + BR/EDR Not Supported） | **必须** |
| Complete List of 128-bit Service UUIDs | 17 | `0x07` + 16 字节 WPS Service UUID（`a7c0a7c0-…`，小端） | **必须** |
| Manufacturer Specific Data | 26 | 见下表（完整 24 字节） | **必须** |
| Complete Local Name | 2 + N | §8.1 的名称 | **应当**（放扫描响应） |

**Manufacturer Specific Data（MSD）布局**（共 **24** 字节）—— 公司标识符用 `0xFFFF`（测试/未注册值）+ 自定义前缀 `"WP"`，因为 WatchPlay 尚未申请 SIG 公司 ID（见 [§11](#11-开放问题--open-questions) Q2）：

| 偏移 | 长度 | 字段 | 说明 |
| :--- | :--- | :--- | :--- |
| 0 | 2 | `company_id` | `0xFF 0xFF` |
| 2 | 2 | `wp_magic` | `0x57 0x50`（`"WP"`） |
| 4 | 1 | `proto_version` | `0x01` = v0.1 |
| 5 | 1 | `shape` | `0x01` 圆屏 / `0x02` 方屏 / `0x03` 圆角矩形 |
| 6 | 2 | `width` | u16 小端，如 466 |
| 8 | 2 | `height` | u16 小端，如 466 |
| 10 | 1 | `codecs` | 位图：bit0 MJPEG，bit1 H.264 |
| 11 | 1 | `bearers` | 位图：bit0 GATT，bit1 CoC，bit2 Wi-Fi |
| 12 | 2 | `max_chunk_bytes` | u16 小端，默认 244 |
| 14 | 2 | `capability_bits` | u16，缩写自 [07-capabilities.md](07-capabilities.md) |
| 16 | 4 | `nonce` | u32，每次启动随机；区分同名设备与去重 |
| 20 | 4 | `state_hash` | u32，对偏移 0–19 的 CRC-16/CCITT-FALSE 值重复两次，便于手机廉价校验 |

**广播预算校验**（AD 总长上限 **31** 字节，按 `[len][type][data]` 计）：Flags 3 + 128 位服务 UUID 18 + MSD 26 = **47 > 31，溢出**。因此 **必须（MUST）** 把整个 MSD 放进扫描响应并开启 Active Scan（手机要连设备本来就该看到扫描响应）。**失败模式**：硬塞进广播会让 `esp_ble_gap_config_adv_data` 返回 `ESP_ERR_INVALID_ARG`，或被栈静默截断——表现为"手表在广播，但手机的过滤条件永远匹配不到"。

**手机侧过滤规则（规范性）**：手机 **必须（MUST）** 在**不建立连接**的前提下完成筛选——(1) 服务 UUID 匹配，(2) `wp_magic` 匹配，(3) `proto_version` 兼容，(4) `shape`/`width`/`height` 满足 UI 需求。**禁止（MUST NOT）** 用"逐个连接再看 `CAPS`"来筛选，那会在多设备场景下耗掉几十秒。

### 8.3 广播参数

| 阶段 | 广播间隔 | 广播类型 | 时长 |
| :--- | :--- | :--- | :--- |
| 快速配对（刚开机 / 用户按下配对） | **100 ms** | ADV_IND | 30 s |
| 常规可发现 | **1000 ms** | ADV_IND | 持续 |
| 已绑定且需被特定手机找回 | **200 ms** | ADV_DIRECT_IND（指向已绑定地址） | 15 s |

取 100 ms 而非最小 20 ms：20 ms 下 ESP32-S3 广播功耗显著上升，而 100 ms 的最坏发现延迟 50 ms 远小于 500 ms 的首帧预算（[00-overview.md §2](00-overview.md#2-设计目标v01)）。

### 8.4 地址与绑定

| 项目 | 要求 | 理由 / 失败模式 |
| :--- | :--- | :--- |
| **地址类型** | 手表 **必须（MUST）** 用**静态随机地址**（`BLE_ADDR_TYPE_RANDOM_STATIC`，高 2 位 `0b11`），由芯片唯一 ID 派生一次并持久化（ESP32-S3 即 eFuse MAC → NVS，见 §2.6） | 公共地址可能与厂商烧写冲突；RPA 每次上电变化会让绑定失效。**禁止（MUST NOT）** 每次上电重新随机——地址改变 = 绑定失效 = 用户必须重配对 |
| **绑定** | **应当（SHOULD）** 支持 LE Secure Connections（Just Works 即可，双方均无输入输出能力） | ① 加密链路是 L2CAP CoC 在部分栈上工作的前提（§3.5）；② 绑定信息让重连跳过服务发现。但 **禁止（MUST NOT）** 把绑定当访问控制：协议本身无认证需求 |
| **重连** | 已绑定手机 **应当（SHOULD）** 用 `autoConnect = true` 直连已知地址，超时 **10 s** 后回退完整扫描 | 只用 autoConnect 会在手表换地址或清空 NVS 后无限失败，用户看不到任何提示 |

---

## 9. 连接生命周期与失败处理

### 9.1 状态机对齐

承载层状态 **必须（MUST）** 与 [03-session.md §1](03-session.md) 的会话状态机一一对齐，**禁止（MUST NOT）** 在本文档另立一套（03 还定义了 `FAILED` 状态；承载层 **禁止（MUST NOT）** 引入"重连中"之类的新状态，用 `CONNECTING` + 重试计数表达）：

| 会话状态 | 承载层对应动作 |
| :--- | :--- |
| `IDLE` | 手表广播中；手机未连接。**接收端（手表）是连接发起方**（[03-session.md §1.2](03-session.md)） |
| `CONNECTING` | GATT 链路建立（手表发起）／CoC 信道建立（手机侧 `createL2capChannel`）；`requestConnectionPriority(HIGH)`；`requestMtu(247)` |
| `HANDSHAKING` | CCCD 已订阅，等待手表发来的 `HELLO` → 手机回 `HELLO_ACK` |
| `NEGOTIATING` | `CAPS`（手表→手机）/ `CAPS_ACK`（手机→手表）交换，确定 `max_chunk_bytes`、`initial_credits`、承载选择 |
| `STREAMING` | 收到 `CONTROL(START)`、首批 Credit 授予之后；视频帧流动 |
| `DRAINING` | **背压状态，不是关闭流程**：停止发送 `VIDEO`，但保持心跳与 `FEEDBACK` 往返，等接收端恢复后回到 `STREAMING` |
| `FAILED` | 承载已断或协议错误；只允许发 `BYE`；由退避重试重新 `CONNECTING` |

### 9.2 超时表

承载层 **禁止（MUST NOT）** 自造与 [03-session.md §1.1/§6.2](03-session.md) 冲突的超时；下表数值与 03 一致，未在 03 中定义的条目为本文档的传输层取值：

| 事件 | 超时 | 超时动作 |
| :--- | :--- | :--- |
| `CONNECTING`（GATT 连接请求）/ 服务发现 | **10 s** | 断开、退避重试（[03 §1.1](03-session.md)） |
| MTU 协商回调 | 2 s | 按 MTU 23 降级继续（**禁止** 因此重连） |
| CoC 建链 | 5 s | 回退 Bearer A，会话继续 |
| `HANDSHAKING`（`HELLO` → `HELLO_ACK`） | **3 s** | 断开、退避重试（[03 §1.1](03-session.md)） |
| `NEGOTIATING`（`CAPS` → `CAPS_ACK`） | **3 s** | `BYE(REASON_TIMEOUT)` 并断开（[03 §1.1](03-session.md)） |
| 心跳间隔 `heartbeat_ms` / 单个 `PING` 等待 `pong_timeout_ms` | **2000 ms / 1000 ms** | 重发 `PING`（[03 §6.2](03-session.md)） |
| `PING` 连续无 `PONG` 次数 | **3 次** | `BYE(REASON_TIMEOUT)` 并断开（[03 §6.2](03-session.md)） |
| 有视频流量时的心跳豁免 `keepalive_grace_ms` | **6000 ms** | 期间抑制心跳；无视频帧满 6 s 后恢复 `PING`（[03 §6.2](03-session.md)） |
| Credit 为 0 的持续时长 | 见 §7.3 第 4 条 | 进入 `DRAINING`（**背压，不断链**）；`drain_timeout_ms` 超限走码率降档 |
| `DRAINING` 等待上限 `drain_timeout_ms` | **2000 ms** | 保持链路与心跳；持续超限则由 [04-video.md §4](04-video.md) 的码率自适应降档，而非断开 |
| 监督超时（链路层判定） | 见 §2.4 | 视为断连，走 §9.4 |

### 9.3 退避重试

```
delay(attempt) = min(30 s, 1 s × 2^attempt) + rand(0 ms, 500 ms)     ← 与 03-session.md §8 完全一致
```

即 attempt 0/1/2/3 分别延迟 `1.0–1.5 s` / `2.0–2.5 s` / `4.0–4.5 s` / `8.0–8.5 s`，attempt ≥ 5 封顶在 `30.0–30.5 s`。规则：

- **发起方是手表**：链路层重连由手表侧重新广播并主动发起；手机侧 **必须（MUST）** 同时接受"手机发起重连已绑定设备"（`autoConnect`）与"等待手表重新广播"两条路径，**禁止（MUST NOT）** 只实现其中一条。
- `attempt` 在**成功进入 `STREAMING` 并稳定 5 s** 后 **必须（MUST）** 清零。**禁止（MUST NOT）** 只要 GATT/TCP 层一连上就清零——那会让"连上就掉"的坏设备永远以 1 s 的节奏重试，把手机电量打光。
- 同一次扫描周期内对同一地址的 `attempt` **禁止（MUST NOT）** 超过 **5** 次；超过后 **必须（MUST）** 重新扫描并允许发现其他候选设备。
- 抖动 **必须（MUST）** 存在：两台设备（例如手表 + 车机）在手机重启后若用固定退避，会持续碰撞重连时机。
- 重连后 **必须（MUST）** 重新走完整握手与能力协商，**禁止（MUST NOT）** 复用上次的 `max_chunk_bytes`/`initial_credits`（[03-session.md §8](03-session.md)）。
- `HELLO_ACK.status = REJECTED` 时 **禁止（MUST NOT）** 自动重连。

### 9.4 断连与半帧

| 情形 | 要求 |
| :--- | :--- |
| 监督超时发生在**帧中间** | 接收端 **必须（MUST）** 丢弃该半帧的全部已收分块（**禁止（MUST NOT）** 尝试用不完整数据解码），并把 `frame_id` 记入统计 |
| 重连后（同一会话或新会话） | 若是 H.264（Profile B），重连后的**第一个视频帧必须（MUST）** 是 IDR 关键帧（`flags & 0x0008`，[03-session.md §8](03-session.md)）；JPEG 恒为关键帧，天然满足 |
| 重连后 H.264 参考状态 | 接收端 **必须（MUST）** 重置解码器参考帧列表；发送端 **应当（SHOULD）** 在首个关键帧上置 `DISCONTINUITY(0x0040)`（[04-video.md §5.3](04-video.md)） |
| 主动断开 | **必须（MUST）** 先发 `BYE` + 原因码（[03-session.md §7](03-session.md)）；收到对方 `BYE` 时 **应当（SHOULD）** 回同 `reason` 的 `BYE`，之后保持链路最多 **500 ms** 再断 |
| 断开时的 Credit 状态 | **必须（MUST）** 全部清零；重连后重新协商并由接收端按新的 `initial_credits` 显式授予（§7.5） |
| 承载切换（`CONTROL(SWITCH_TRANSPORT)`，如 GATT → CoC） | **禁止（MUST NOT）** 视为断连；**禁止（MUST NOT）** 重置 `seq`、`frame_id`、Credit **之外的**会话状态；Credit **必须（MUST）** 重置并按新承载重新授予。切换前后的丢帧边界见 [§11](#11-开放问题--open-questions) Q9 |

---

## 10. 一致性检查清单

一个承载实现要声称"WatchPlay v0.1 传输层兼容"，**必须（MUST）** 通过以下全部条目：

| ID | 要求 |
| :--- | :--- |
| **T-01/T-02** | 至少实现 Bearer A（BLE GATT：`a7c0a701` 写 + `a7c0a702` 通知），完成完整握手与推流；广播中含 WPS Service 128 位 UUID 与 MSD（`wp_magic`=`"WP"`、`proto_version`、`shape`、`width`、`height`） |
| **T-03** | 手机端在不建立连接的前提下，仅凭广播/扫描响应即可筛选出兼容设备 |
| **T-04/T-05** | 正确处理 `requestMtu` 返回 23 / 247 / 517 三种情形：**必须（MUST）** 按 `max_chunk_bytes = min(244, MTU − 16)` 分块并写入 `CAPS_ACK.RESULT.max_chunk_bytes`；MTU 23（上界 7 < 84 下限）时 **必须** 以 `BANDWIDTH_INSUFFICIENT`/`RESOLUTION_UNSUPPORTED` 拒绝，**禁止** 静默按 244 分块；`bearer_tu = MTU − 3` 的承载级分片 **必须** 与协议级分块区分开 |
| **T-06** | 分块算术正确：默认 `max_chunk_bytes = 244` 时 8123 字节 JPEG 得到 **37** 块，最后一块 `payload_len = 79` |
| **T-07/T-08** | 完整实现 [02-wire-format.md §7](02-wire-format.md#7-解析伪代码) 的增量解析器（`hdr_crc` 失败即丢整帧，**禁止** 部分解析）与 [§7.1](02-wire-format.md#71-重新同步resync) 的 resync；5 秒内 32 次 resync 时以 `BYE(REASON_PROTOCOL_ERROR)` 断开 |
| **T-09/T-10** | 超过 `bearer_tu` 的协议帧被正确切分并提交；接收端仅靠字节累加即可重组，**无任何自定义长度前缀**；帧头层重组缓冲上限 `2 × (16 + max_chunk_bytes)`，超限触发 resync 而非无界增长 |
| **T-11/T-12** | Credit 为 0 时停止发送视频帧（**不得** drop-and-continue）；Credit 授予可幂等重放，消耗至 `initial_credits/4` 以下时补授回 `initial_credits`，为 0 持续 500 ms 时重发当前绝对值 |
| **T-13/T-14** | 进入 `STREAMING` 前调用 `requestConnectionPriority(HIGH)`，无数据超过 5 s 后切回 IDLE 参数；GATT 133 每次重试前 `close()` 旧实例，链路重连退避为 `min(30 s, 1 s × 2^n) + jitter`（与 03 §8 一致），同一设备 5 次后 30 s 冷却并重新扫描 |
| **T-15/T-16** | 未知 `type`（`0x0D–0x7F`、`0x80–0xFF`）按 `hdr_len`/`payload_len` 跳过且 **不得** 断连；监督超时发生在帧中间时丢弃半帧，重连后 H.264 第一帧为 IDR 并置 `DISCONTINUITY` |
| **T-17/T-18** | 广播地址为静态随机地址且跨上电稳定（`addr4` 与设备名一致）；断开后 Credit 与分片队列全部清零，重连后由接收端重新显式授予 |
| **T-19** | （实现 Bearer B 时）正确处理 L2CAP Credit 与 WatchPlay Credit 两层独立流控；L2CAP 层耗尽时 **不得** 阻塞 BT 线程 |
| **T-20** | （实现 Bearer C/TCP 时）设置 `TCP_NODELAY`；使用同一增量解析器而非"一次读=一帧"；**不得** 依赖自定义长度前缀 |
| **T-21** | （实现 Bearer D 时）SDP 记录的 `ServiceClassIDList` 含 `a7c0a705`，`ProtocolDescriptorList` 含 RFCOMM channel；手机 **仅凭 SDP** 即可判定兼容设备，**不得** 要求对方先被 BLE 扫描到 |
| **T-22** | （实现 Bearer D 时）在 RFCOMM 的字节流上运行 §6.3 的增量解析器，**不得** 一次读即一帧、**不得** 加自定义长度前缀；`bearer_tu` 通过 `TRANSPORT.ext` 前 2 字节上报，`TRANSPORT.mtu` 填 255 |
| **T-23** | （实现 Bearer D 时）RFCOMM credit 与 WatchPlay Credit 按两层处理，应用层 Credit 为唯一权威；不在 BT 回调线程写 socket；读线程 EOF 时丢弃半帧 |

---

## 11. 开放问题 / Open questions

以下均为 v0.1 **未决** 的真实问题，**不是** 实现者的自由发挥空间——在 v0.2 定案前，实现者 **应当（SHOULD）** 按本文档的建议值实现，并把差异记录进 [99-changelog.md](99-changelog.md)。

| # | 问题 | 相关方 |
| :--- | :--- | :--- |
| Q1 | **UUID 分配流程**：当前使用自造的 `a7c00000-6cc1-4a51-8c1b-7b3f7ba7e0b2` 基 UUID 与 `A7C0` 功能码。是否向 Bluetooth SIG 申请 16 位 UUID？是否改用 SIG 测试用基 UUID `0000xxxx-0000-1000-8000-00805F9B34FB` 以便使用 16 位简写（风险：`A7C0` 可能与 SIG 未来分配冲突）？ | 协议维护者 |
| Q2 | **`company_id = 0xFFFF`**（[§8.2](#82-广播载荷布局)）：`0xFFFF` 是保留/测试值，可能被某些手机的扫描过滤器忽略或误判。是否需要申请真实 SIG 公司 ID？ | 协议维护者 |
| Q3 | ✅ **已决议（Resolved）**：**`initial_credits` 默认 = 64**，由接收端在 `CAPS_ACK.RESULT.initial_credits` 中给出（[03-session.md §4](03-session.md)，协商范围 `clamp(free_queue_slots, 16, 128)`），与 [04-video.md §4.1](04-video.md) 一致。补授阈值 = `W0/4`，补授目标 = `W0`。本文档 §7 已按 64 更新，保留本行仅为历史记录 | — |
| Q4 | ✅ **已决议（Resolved）**：`FEEDBACK.credits` 是**绝对值**（当前可用 Credit 总数，不是增量），由 [04-video.md §4.2](04-video.md) 定义、本文档 §7.1/§7.5 引用。保留本行仅为历史记录 | — |
| Q5 | **`max_chunk_bytes` 是否从 244 降到 228**（[§6.1](#61-术语与算术)）：244 会让 260 字节的协议帧在 `bearer_tu = 244` 下多一次 16 字节尾片提交；228 可做到"恰好一片"，代价是分块数 +7%。两者都合规（[03-session.md §4.2](03-session.md) 的上界为 `MTU − 16`），仅为效率取舍 | 本文 + 03 + 04 |
| Q6 | **Wi-Fi 交接是否标准化**（[§4.1](#41-拓扑与发现)）：凭据 TLV 归属 [07-capabilities.md](07-capabilities.md)，但交接流程（超时、回退、凭据加密要求）未标准化。同时 [00-overview.md §5](00-overview.md#5-一致性) 的一致性段落未提及 Wi-Fi 承载的合规要求 | 本文 + 03 + 07 |
| Q7 | **是否为 H.264 引入应用层 ARQ**（[§4.3](#43-tcp_nodelay-与队头阻塞)）：TCP 队头阻塞对实时视频不利；UDP + 选择性重传需要一个承载无关的"帧有效性"判定点，与 [04-video.md](04-video.md) 的丢帧策略耦合 | 本文 + 04 |
| Q8 | **ESP32-S3 的 CoC MTU 是否提升到 1024**（[§3.4](#34-sdu--mtu-建议)）：当前建议封顶 512（internal RAM）。若启用 `CONFIG_SPIRAM_USE_MALLOC` 让 CoC 缓冲进 PSRAM，需实测 PSRAM 争用对 JPEG 硬解的影响 | 手表端实现 |
| Q9 | **`seq` 在承载切换时是否必须重置**（[§3.2](#32-建立流程) 步骤 5）：本文要求不重置；若接收端在切换期间漏掉若干帧，`lostFrames` 会误报，是否需要一个"承载切换分隔帧"？ | 本文 + 03 |
| Q10 | **PSM 传输位置**（[§3.2](#32-建立流程) 步骤 2）：`a7c0a704` 特征值 vs `CAPS` TLV，本文禁止同时使用，哪种是长期方案？ | 本文 + 07 |
| Q11 | **广播间隔与功耗的实测取舍**（[§8.3](#83-广播参数)）：本文给出 100 ms/1000 ms 的建议，但缺少 ESP32-S3 AMOLED 板在两种间隔下的实测平均电流 | 手表端实现 |
| Q12 | **经典蓝牙的配对方式是否强制**：Just Works 无需输入、Numeric Comparison 需要屏幕与按键。是否要求 Bearer D 必须用 Numeric Comparison，还是允许 Just Works 加应用层二次确认？ | 协议维护者 |
| Q13 | **`bearer_tu` 的建议值 990 是否合适**（[§5.2](#52-bearer_tu-与上报)）：RFCOMM 的 N1 默认 127，990 依赖双方都肯协商；若某栈只给 127，`max_chunk_bytes` 反被压到 111。需要各平台的实际协商结果 | 手表端实现 |
| Q14 | **双模设备该选哪个承载**：SPP 吞吐高，GATT 更省电、发现更灵活。是否需要按场景切换的建议（前台推流用 SPP、息屏心跳用 GATT），还是交给 `preferred` 静态决定？ | 本文 + 03 + 07 |

### 已知的文档间不一致

✅ **当前无未解的不一致。** 此前记录的两项已由维护者修正，留档如下：

| 位置 | 曾经的差异 | 状态 |
| :--- | :--- | :--- |
| [00-overview.md §3](00-overview.md#3-文档地图)、[§5](00-overview.md#5-一致性) | 曾把线格式写成 "**CRC32**"，而 [02-wire-format.md §2](02-wire-format.md#2-crc16-校验) 权威定义为 **CRC-16/CCITT-FALSE** 且只覆盖帧头 | ✅ 已修正为 CRC-16/CCITT-FALSE（以 02 为准） |
| [00-overview.md §2](00-overview.md#2-设计目标v01) | "传输缓冲 < 2 MB" 的措辞易与本文档的帧头层承载缓冲（`2 × (16 + max_chunk_bytes)` = **520 字节**）混淆；前者实为**视频层整帧重组缓冲** | ✅ 已改写措辞，两者不再冲突 |

实现者若发现新的跨文档矛盾，**必须（MUST）** 先在 [99-changelog.md](99-changelog.md) 登记，**禁止（MUST NOT）** 私自选一个版本实现。

---

> 相关文档：[00-overview.md](00-overview.md)（总览与设计目标）、[02-wire-format.md](02-wire-format.md)（**权威** 帧头/CRC/`seq`/resync/缓冲上限）、[03-session.md](03-session.md)（会话状态机，本文 §9.1 与之对齐）、[04-video.md](04-video.md)（视频子头、`FEEDBACK` 字段布局、丢帧策略）、[05-input.md](05-input.md)、[06-rendering.md](06-rendering.md)、[07-capabilities.md](07-capabilities.md)（能力位与 TLV）、[99-changelog.md](99-changelog.md)（变更记录与实现差异登记）、[../guides/getting-started.md](../guides/getting-started.md)。
