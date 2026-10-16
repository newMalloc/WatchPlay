# WatchPlay

**手机 → 手表投屏协议标准与参考实现**

用你手机 App 里的 HTML + CSS 渲染一块专为手表设计的界面，编码后经蓝牙投到手表上显示；手表的触控回传手机，形成交互闭环。

**不需要 MediaProjection 录屏权限。** 画面来自 App 自己渲染的手表 UI 场景，而不是整块手机屏幕。

[协议规范](docs/spec/00-overview.md) · [快速开始](docs/guides/getting-started.md) · [路线图](#路线图) · [贡献指南](CONTRIBUTING.md)

---

## 为什么不是"把手机屏幕投上去"

| | 传统投屏（MediaProjection） | WatchPlay |
| :--- | :--- | :--- |
| 画面来源 | 整块手机屏幕，缩放到手表 | App 自己渲染的手表 UI 场景 |
| 权限 | 需要录屏授权弹窗，属敏感权限 | **不需要**任何录屏/隐私权限 |
| 分辨率 | 手机原生，需缩放，文字必糊 | 按手表分辨率 1:1 渲染 |
| 交互 | 只能看 | 触控回传，闭环 |
| 适用场景 | 任意手机内容 | 为手表**专门设计**的界面 |

核心洞察：**手表屏幕小 → UI 简单 → 可以用 HTML+CSS 描述 → 可以按手表分辨率 1:1 渲染，而不必录制整块手机屏再缩放。** 这一步消除掉了 90% 的带宽与合规问题。

## 架构

```
手机端 (Android)                              手表端 (ESP32-S3 等)
┌──────────────────────────────┐              ┌──────────────────────────┐
│ HTML+CSS 手表 UI             │              │  传输层                  │
│      │ WebView               │              │      │                   │
│      ▼                       │              │      ▼                   │
│  OpenGL 绘制到纹理            │              │  协议解包 / 重组          │
│      │                       │              │      │                   │
│      ▼                       │              │      ▼                   │
│  MediaCodec 编码             │   BLE        │  解码 (JPEG硬解/H.264)    │
│  (JPEG / H.264)              │◄────────────►│      │                   │
│      │                       │  GATT/CoC    │      ▼                   │
│  协议封装 / Credit 流控       │              │  PSRAM 双缓冲            │
│      │                       │              │      │  (TE 同步)        │
│      ▼                       │              │      ▼                   │
│  TransportModule             │              │  466×466 圆屏            │
│      ▲                       │              │      │                   │
│      │                       │              │      ▼                   │
│  触控映射 → 注入 WebView      │              │  触控采集 (FT3168)        │
└──────────────────────────────┘              └──────────────────────────┘
```

音频说明：ESP32-S3 **只有 BLE，没有经典蓝牙（A2DP）**。参考实现中音频由手机本地播放，手表只负责显示与触控。带经典蓝牙的芯片（如炬芯 ATS3089）可走 A2DP 独立链路。

## 双编码 Profile

| Profile | 适用芯片 | 编码 | 带宽 | 手表端解码 |
| :--- | :--- | :--- | :--- | :--- |
| **A: MJPEG** | 低端 MCU（ESP32-S3） | 手机端按需压 JPEG | 150–300 KB/s @ 10 fps | JPEG 硬解，10–15 fps |
| **B: H.264** | 中高端（带硬解） | MediaCodec Baseline | 200–800 kbps | 硬解，15–30 fps |

Profile A **必须**支持；Profile B 可选。H.264 低延迟配置：Baseline、禁 B 帧、GOP 8–12、VBR、剥离起始码。

> `Negotiator` **只在手表声明 `hardware_decode == 1` 时才选 H.264**。ESP32-S3 会上报 `hardware_decode = 0`，因此自然退回 MJPEG —— 这是协议里最重要的一条启发式规则，避免用户得到"支持 H.264 却更卡"的结果。

## 仓库结构

```
docs/
  spec/                    协议规范（权威）
    00-overview.md           总览与设计目标
    01-transport.md          承载：BLE GATT / L2CAP CoC / Wi-Fi
    02-wire-format.md        16 字节帧头、CRC、帧类型  ← 先读这个
    03-session.md            握手、能力协商、状态机、心跳
    04-video.md              分块、Credit 流控、丢帧恢复
    05-input.md              触控回传与坐标映射
    06-rendering.md          圆屏/方屏安全区与 CSS 契约
    07-capabilities.md       能力位、TLV 注册表、版本兼容
    99-changelog.md          协议变更记录
  guides/                  面向使用者的指南
android/
  sdk/                     手机端 Kotlin SDK（com.watchplay.sdk）
  demo/                    示例 App
watch/                     ESP32-S3 手表端参考实现
tools/                     Python 协议参考实现与工具
examples/
  watch-ui/                示例手表 UI（HTML+CSS）
  protocol-traces/         权威字节级 trace（自动生成）
```

## 快速开始

手机端协议栈（纯 Python，无需硬件，可验证协议实现是否正确）：

```pwsh
git clone <this-repo>
cd WatchPlay
python tools/test_protocol.py        # 46 项协议一致性测试
python tools/gen_traces.py --check   # 校验提交的 trace 与规范一致
python tools/lint_scenes.py          # 校验示例场景符合渲染规范
python tools/check_doc_links.py      # 校验文档交叉引用与锚点
```

手表端与手机端的构建方式见 [快速开始指南](docs/guides/getting-started.md)。

## 路线图

| 阶段 | 目标 |
| :--- | :--- |
| 第一阶段 | 协议 v0.1，MJPEG 端到端 Demo，466×466 @ 10 fps，延迟 < 200 ms |
| 第二阶段 | 地图导航 + 音乐播放，H.264 支持，Wi-Fi 通道 |
| 第三阶段 | Android SDK 发布，协议 v1.0，兼容性测试套件，iOS 启动 |
| 第四阶段 | 厂商接入，WatchPlay Certified 认证 |

### 里程碑

| 里程碑 | 交付物 | 状态 |
| :--- | :--- | :--- |
| M1 | MJPEG 投屏 Demo | 进行中：协议规范 v0.1 已完成 |
| M2 | 地图导航 + 音乐播放 | |
| M3 | H.264 + Wi-Fi 通道 | |
| M4 | Android SDK | |
| M5 | 协议 v1.0 + 认证计划 | |
| M6 | 首个厂商接入 | |

## 参考硬件

| 端 | 硬件 |
| :--- | :--- |
| 手表 | LilyGO T-Display S3 AMOLED 1.43 — ESP32-S3R8, 8 MB PSRAM, 466×466 圆屏, FT3168 触摸 |
| 手机 | 任意 Android 8.0+（`minSdk 26`） |

## 参与

- **问题与 bug**：用 [Issues](https://github.com/newMalloc/WatchPlay/issues)。这是目前唯一的正式渠道。
- 协议提案 **必须** 先在 [07-capabilities.md](docs/spec/07-capabilities.md) 登记编号，再提交 PR。见 [贡献指南](CONTRIBUTING.md)。

> GitHub Discussions 与 Discord 频道尚未开设。在它们开通之前，请统一走 Issues——不开着入口却写在这里，只会让人提了没人看。

初期由核心维护者决策，成熟后转向社区治理。

## 许可证

[Apache License 2.0](LICENSE)。协议规范文本同样以 Apache 2.0 发布，允许任何人在其之上实现兼容产品。
