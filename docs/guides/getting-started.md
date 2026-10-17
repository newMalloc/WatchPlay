# 快速开始

本指南带你从零跑通 WatchPlay，按**由易到难**的顺序组织。每一步都能独立验证，不需要等前面全部做完。

## 目录

- [第 0 步：验证协议实现](#第-0-步验证协议实现无硬件)
- [第 1 步：看懂一帧数据](#第-1-步看懂一帧数据)
- [第 2 步：跑通手机端（无手表）](#第-2-步跑通手机端无手表)
- [第 3 步：跑通手表端](#第-3-步跑通手表端)
- [第 4 步：端到端联调](#第-4-步端到端联调)
- [常见问题](#常见问题)

---

## 第 0 步：验证协议实现（无硬件）

`tools/` 是协议的**可执行参考实现**，只依赖 Python 标准库。这一步不需要 Android SDK、不需要 ESP-IDF、不需要任何硬件，是验证"协议本身是否自洽"最快的方式。

```pwsh
git clone <this-repo>
cd WatchPlay

python tools/test_protocol.py
```

期望输出：

```
Ran 31 tests in 0.03s

OK
```

这 31 项测试覆盖了帧头布局、CRC 校验、序列号回绕、字节流重同步、未知帧类型容忍、以及提交的 trace 文件能否被正确重组。

再验证提交的 trace 与协议规范一致：

```pwsh
python tools/gen_traces.py --check
```

```
check: OK
```

如果这两个命令有一个失败，**先别往下走**——说明仓库状态不对（或者你正在改协议，需要同步更新规范与 trace）。

### 编译期常见问题

| 症状 | 原因 |
| :--- | :--- |
| `python: command not found` | 用 `py -3` 代替 `python`（Windows）或检查 PATH |
| Unicode 编码错误（Windows 控制台） | `$env:PYTHONUTF8=1` |

---

## 第 1 步：看懂一帧数据

WatchPlay 的一切都建立在一个 **16 字节定长帧头** 上。先把它看懂，后面所有东西都会变得显然。

```
偏移  长度  字段           本帧的值
   0     2  magic          57 50            "WP"
   2     1  version        01               v0.1
   3     1  type           05               VIDEO
   4     2  flags          18 00            0x0018 = KEYFRAME | FIRST_CHUNK
   6     2  hdr_len        10 00            16
   8     4  seq            2A 00 00 00      42
  12     2  payload_len    F4 00            244
  14     2  hdr_crc        5D F5            CRC-16/CCITT-FALSE
  16     …  payload        …
```

拿真实数据自己验一遍（`--check` 用的就是这些字节）：

```pwsh
python -c "
import sys; sys.path.insert(0, 'tools')
from watchplay_proto import *

# 1) CRC 的标准校验值，任何实现都必须得到这个数
print('crc16(b\'123456789\') =', hex(crc16_ccitt_false(b'123456789')))   # 0x29b1

# 2) 解析 trace 的第一帧，看看帧头字段
data = open('examples/protocol-traces/mjpeg-frame-466x466-3c.bin','rb').read()
h = parse_header(data[:16])
print('type =', FrameType(h.type).name, ' seq =', h.seq, ' payload_len =', h.payload_len)
print('flags =', hex(h.flags))

# 3) 读完整个文件，检查有没有 CRC 错误
p = StreamParser(); frames = p.feed(data)
print('chunks =', len(frames), 'errors =', p.stats())

# 4) 重组出原始 JPEG 字节数
jpeg = b''.join(f.payload[20:] for f in frames)   # 每块去掉 20 字节视频子头
print('reassembled =', len(jpeg), 'bytes')
"
```

期望输出：

```
crc16(b'123456789') = 0x29b1
type = VIDEO  seq = 42  payload_len = 244
flags = 0x18
chunks = 37 errors = {'frames': 37, 'hdr_crc_errors': 0, 'resync_count': 0, 'unknown_frames': 0, 'version_errors': 0}
reassembled = 8123 bytes
```

**这里最容易踩的坑**：8123 字节的帧被切成 **37** 块而不是 34 块。因为 `max_chunk_bytes = 244` 限制的是 **`payload_len`**，而 `payload_len` **包含 20 字节的视频子头**，所以每块只能装 224 字节媒体数据：

```
chunk_total = ceil(8123 / (244 - 20)) = ceil(8123 / 224) = 37
```

用 `ceil(8123 / 244) = 34` 的实现会在第 34 块就认为帧结束，解出一张被截断的 JPEG。详见 [04-video.md §3.1](../spec/04-video.md)。

### 建议的阅读顺序

- 只想知道怎么对接：**[02-wire-format](../spec/02-wire-format.md)** → [03-session](../spec/03-session.md) → [07-capabilities](../spec/07-capabilities.md)
- 写手表端固件：[02](../spec/02-wire-format.md) → [01-transport](../spec/01-transport.md) → [03](../spec/03-session.md) → [04](../spec/04-video.md) → [05](../spec/05-input.md) → [06](../spec/06-rendering.md)
- 写手机端 App：[02](../spec/02-wire-format.md) → [03](../spec/03-session.md) → [04](../spec/04-video.md) → [06](../spec/06-rendering.md) → [05](../spec/05-input.md)

---

## 第 2 步：跑通手机端（无手表）

手机端是 Kotlin 模块，包含纯逻辑的协议栈、以及依赖 Android 的渲染/编码/传输。**协议逻辑部分可以在 JVM 上单元测试，不需要设备**。

### 环境

| 工具 | 版本 |
| :--- | :--- |
| JDK | 17（Android Studio 自带的 JBR 即可） |
| Android SDK | Platform 34 + Build-Tools 34.0.0 |
| Gradle | 用仓库里的 wrapper，无需本地安装 |

设置 SDK 路径（二选一）：

```pwsh
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

或创建 `android/local.properties`（**此文件不提交**）：

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

### 构建与测试

```pwsh
cd android
./gradlew :sdk:test          # 97 项协议逻辑单元测试，不需要设备
./gradlew :sdk:assembleDebug
```

### 用 Loopback 跑一个不需要手表的端到端

`demo` 模块内置 `LoopbackTransport`：它把"手机 → 手表"的字节流直接回灌给本机的一个"假手表"，因此你可以在没有硬件的情况下验证整条管线（渲染 → 捕获 → 编码 → 分块 → 重组 → 解码）。

```pwsh
cd android
./gradlew :demo:installDebug
```

在设备上打开 Demo App，点 **Start mirroring**。你会看到：

- 上方 WebView 里是一个动画的手表界面（这就是"手表节目"的来源）
- 下方的统计文本持续刷新：`fps`、`bytes/s`、`dropped`、`latency`
- 因为走的是 loopback，`latency` 只反映管线内部开销，不含蓝牙

**这一步能验证**：WebView 渲染是否正常、OpenGL 捕获是否出图、编码器是否初始化成功、分块与 Credit 逻辑是否自洽。**不能验证**：真实蓝牙吞吐与手表解码性能。

### 用第二台 Android 设备当手表（真实 BLE 链路）

`sink` 模块就是手表那一端：它开一个 GATT server、广播 WatchPlay 服务、重组视频帧、把 JPEG 解出来显示，并把触摸回传。两台 Android 设备就能跑真实射频链路，不必先有手表硬件。

```pwsh
cd android
./gradlew :sink:installDebug      # 装在"手表"那台
./gradlew :demo:installDebug      # 装在手机那台
```

| 端 | 操作 | 期望 |
| :--- | :--- | :--- |
| 手表那台 | 打开 **WatchPlay Sink**，授予蓝牙权限（CONNECT + ADVERTISE） | 状态栏显示 `advertising as a 480px round sink`，链路 `OPENING` |
| 手机那台 | 打开 Demo，点 **Connect to watch (BLE)**，授予扫描/连接权限 | 8 秒内弹出设备列表 |
| 手机那台 | 选中列表里的 sink | 会话进入 `STREAMING`，状态区出现 `link: BLE …` |
| 手表那台 | 屏幕上出现镜像画面，点它 | 手机端 WebView 的场景响应触摸 |

**这条路径能验证**：真实 MTU 协商、`bearer_tu` 分片、CCCD 订阅、Credit 往返、JPEG 解码与触控回传。**不能验证**：真手表芯片的解码性能与功耗，那要等第 3 步的板子。

sink 刻意做得简单：480 px 圆屏、只解 JPEG（`BitmapFactory` 没有 H.264）、单点触控。

---

## 第 3 步：跑通手表端

本节是**参考实现**的一条路径，绑定一块具体的板：LilyGO T-Display S3 AMOLED 1.43（ESP32-S3R8、8 MB PSRAM、466×466 圆屏、FT3168 触摸）。换成别的芯片或只做经典蓝牙，协议层完全一样，需要改的是 [01-transport.md](../spec/01-transport.md) 里选哪个 Bearer 与 [watch/docs/reference-architecture.md](../../watch/docs/reference-architecture.md) 里的任务划分。

### 环境

| 工具 | 版本 |
| :--- | :--- |
| ESP-IDF | v5.1 或更高 |
| 目标 | `esp32s3` |

```pwsh
# 激活 ESP-IDF 环境（按你的安装路径调整）
. $env:IDF_PATH\export.ps1

cd watch
idf.py set-target esp32s3
idf.py menuconfig      # 检查 PSRAM 已启用、Flash 大小正确
idf.py build
idf.py -p COM<N> flash monitor
```

### 必须确认的配置

| 配置项 | 值 | 为什么 |
| :--- | :--- | :--- |
| PSRAM | **Enabled, Octal SPI** | 双缓冲需要 848 KB，内部 RAM 不够 |
| CPU 频率 | 240 MHz | 466×466 JPEG 解码需要余量 |
| FreeRTOS tick | 1000 Hz | 10 fps 的帧调度需要 1 ms 粒度 |
| 主任务核 | Core 0（BLE）/ Core 1（解码+刷屏） | 见 [04-video.md §8.1](../spec/04-video.md) |

### 启动日志期望内容

```
I (312) WP_BLE: advertising as "WatchPlay-TDisp"
I (450) WP_PROTO: HELLO sent, nonce=0x8F2A1C04
I (620) WP_PROTO: HELLO_ACK ok, sdk=0.1.0
I (780) WP_NEG: CAPS sent (466x466 round, MJPEG, psram=8MB)
I (950) WP_NEG: CAPS_ACK: MJPEG 466x466 @10fps q=72 chunk=244 credits=64
I (1100) WP_VIDEO: frame 0 decoded in 4.2ms
I (1120) WP_VIDEO: frame 0 presented (te=1)
```

如果卡在 `HELLO sent` 没有后续，问题在 BLE 链路；如果卡在 `CAPS sent`，问题在手机端协商逻辑。

---

## 第 4 步：端到端联调

1. 手表上电，确认在广播（`WatchPlay-TDisp`）；或装 `sink` 的第二台 Android 设备在广播。
2. 手机打开 Demo App，点 **Connect to watch (BLE)**，扫描并连接该设备。
3. 观察手表屏幕出现画面，触摸屏幕，确认手机端 UI 有反应。

### 该测什么

| 指标 | 目标（M1） | 怎么测 |
| :--- | :--- | :--- |
| 帧率 | 10 fps | 手机端 `PipelineStats.fps` |
| 端到端延迟 | < 200 ms | 手表拍一段高速视频，对比手机 UI 变化与手表变化 |
| 带宽 | 150–300 KB/s | 手机端 `PipelineStats.bytesPerSecond` |
| 丢帧率 | < 2% | 手机端 `PipelineStats.dropped` / 总帧数 |
| 首帧时间 | < 500 ms | 从点击连接到手表出图的时间 |
| CPU（手表 Core 1） | < 70% | `idf.py monitor` 里的任务统计 |

### 用 PC 当"旁观者"验证协议

Android 的 `btsnoop_hci.log` 里**没有** GATT 通知的 ATT 负载——它只记录 HCI 命令与事件，解不出协议帧。要拿到可分析的字节，走这两条：

| 方式 | 怎么做 | 产出 |
| :--- | :--- | :--- |
| 抓包 | nRF Connect 的 BLE 抓包；或手机 HCI snoop 配合 `btmon` 重建 L2CAP | `.pcap` |
| 自己 dump | 在手机 SDK 或手表固件里把协议层字节原样写到文件 | `.bin` / `.hex` |

然后：

```pwsh
python tools/analyze_stream.py capture.pcap
```

输出：

- 帧类型直方图
- 有没有 CRC 错误、有没有发生重同步
- 实测 `chunk_total` 与 `frame_bytes`（用于验证 off-by-20 类问题）
- Credit 的授予/消耗曲线
- 实测帧率与码率

**这是排查"画面不对"最快的手段**——大多数问题在字节层面就已经可见了。

---

## 常见问题

### 手表连不上 / 连上就断

- 确认手机和手表都支持同一协议版本。手表日志里的 `HELLO` 版本与手机 SDK 支持的版本要能相交。
- 检查是否被 `HELLO_ACK(status = REJECTED)` 拒绝——此时**禁止**自动重连，需要用户重新授权。
- GATT 133 错误在 Android 上很常见，通常是设备缓存问题：关掉手机蓝牙再开，或清除 App 的蓝牙缓存。

### 有画面但很卡 / 花屏

按可能性排序：

1. **带宽不足**：看 `PipelineStats.bytesPerSecond` 是否超过 BLE 实际吞吐。降质量或降帧率。
2. **off-by-20 分块错误**：表现为画面下半部灰掉或解码失败。用 `analyze_stream.py` 检查 `chunk_total`。
3. **Credit 没有归还**：看 `FEEDBACK.credits` 是否长期贴 0。检查手表是否在**解码完成后**才归还 Credit（而不是收到块就归还）。
4. **双缓冲没生效**：表现为撕裂。确认 PSRAM 已启用、`tearing_mode` 与 TE 引脚配置正确。
5. **Core 绑定错误**：解码和 BLE 抢同一个核。检查任务创建时的 `xTaskCreatePinnedToCore` 参数。

### 圆屏上内容被切掉

- 检查手表上报的 `DISPLAY.safe_inset_permille`。圆屏默认应为 `140`（14%），方屏 `40`（4%）。
- 手机**必须**采信手表上报的值，不要自己猜。
- 圆屏内接正方形的硬极限是 `(1 − 1/√2)/2 ≈ 14.64%`，超过这个值就一定有内容被切。见 [06-rendering.md](../spec/06-rendering.md)。

### 触摸没反应 / UI 卡在按下状态

- 检查 `HELLO.flags` 是否置了 `HAS_TOUCH`。
- 检查坐标是否为**手表物理像素原始坐标**——手表侧不应做任何缩放。
- 如果 UI 卡在按下状态，说明 `UP` 丢了。协议规定手表 5 秒兜底补发 `UP`、手机 10 秒兜底合成 `CANCEL`，检查这两条是否实现。

### 音视频不同步 / 没有声音

ESP32-S3 **只有 BLE，没有经典蓝牙，无法 A2DP**。参考实现中音频由**手机本地播放**，手表只负责显示与触控。这不是 bug，是硬件限制。见 [04-video.md](../spec/04-video.md) 与 [03-session.md §3.7](../spec/03-session.md)。

### 想看协议细节

- 字节布局：[02-wire-format.md](../spec/02-wire-format.md)
- 状态机与握手：[03-session.md](../spec/03-session.md)
- 分块与流控：[04-video.md](../spec/04-video.md)
- 能力协商：[07-capabilities.md](../spec/07-capabilities.md)
- 变更历史：[99-changelog.md](../spec/99-changelog.md)

---

## 下一步

- 想贡献协议：先读 [CONTRIBUTING.md](../../CONTRIBUTING.md) 的"协议变更流程"——任何新增编号都必须先在 [07-capabilities.md](../spec/07-capabilities.md) 登记。
- 想写手表 UI：见 [06-rendering.md](../spec/06-rendering.md) 的 CSS 契约与 `examples/watch-ui/` 的示例。
- 想接自己的硬件：见 [07-capabilities.md §6](../spec/07-capabilities.md) 的实现矩阵，找到你的档位。
