# WatchPlay

手机 → 手表投屏协议与参考实现。

手机 App 用 HTML + CSS 渲染手表界面，经 WebView 捕获后编码为 MJPEG/H.264，通过 BLE 发送到手表显示；手表触控回传手机。不使用 MediaProjection 录屏，不涉及屏幕录制权限。

[协议规范](docs/spec/00-overview.md) · [快速开始](docs/guides/getting-started.md) · [贡献指南](CONTRIBUTING.md)

---

## 与传统投屏的区别

| | MediaProjection 投屏 | WatchPlay |
| :--- | :--- | :--- |
| 画面来源 | 整块手机屏幕，缩放到手表 | App 自行渲染的手表 UI |
| 权限 | 录屏授权弹窗 | 无 |
| 分辨率 | 手机原生，需缩放 | 按手表分辨率渲染 |
| 交互 | 单向 | 触控回传 |

## 架构

![WatchPlay 架构](docs/diagrams/architecture.svg)

图源是 [docs/diagrams/architecture.mmd](docs/diagrams/architecture.mmd)。这里用图片而不是内联 Mermaid：GitHub 的 Mermaid 渲染器会忽略 subgraph 的 `direction`，带跨栏连线的图会被摊平成几千像素宽的横带，缩到正文宽度后文字不可读。

音频：ESP32-S3 无经典蓝牙，不支持 A2DP，音频由手机本地播放。带经典蓝牙的芯片（如炬芯 ATS3089）可走 A2DP 独立链路。

## 编码 Profile

| Profile | 适用芯片 | 编码 | 带宽 | 手表端解码 |
| :--- | :--- | :--- | :--- | :--- |
| **A: MJPEG** | 低端 MCU（ESP32-S3） | 手机端压 JPEG | 150–300 KB/s @ 10 fps | JPEG 硬解，10–15 fps |
| **B: H.264** | 中高端（带硬解） | MediaCodec Baseline | 200–800 kbps | 硬解，15–30 fps |

ESP32-S3 只能软解 H.264，因此上报 `hardware_decode = 0`，协商时自动退回 Profile A。参数细节见 [04-video.md](docs/spec/04-video.md)。

## 仓库结构

```
docs/
  spec/                    协议规范（权威）
    00-overview.md           总览与设计目标
    01-transport.md          承载：BLE GATT / L2CAP CoC / Wi-Fi
    02-wire-format.md        16 字节帧头、CRC、帧类型
    03-session.md            握手、能力协商、状态机、心跳
    04-video.md              分块、Credit 流控、丢帧恢复
    05-input.md              触控回传与坐标映射
    06-rendering.md          圆屏/方屏安全区与 CSS 契约
    07-capabilities.md       能力位、TLV 注册表、版本兼容
    99-changelog.md          协议变更记录
  diagrams/                文档插图（Mermaid 源 + 渲染好的 SVG）
  guides/                  使用指南
android/
  sdk/                     手机端 Kotlin SDK（com.watchplay.sdk）
  demo/                    示例 App
watch/                     ESP32-S3 手表端参考实现
tools/                    协议参考实现与校验工具（仅标准库）
examples/
  watch-ui/                示例手表 UI（HTML+CSS）
  protocol-traces/         字节级 trace（由 tools/gen_traces.py 生成）
```

## 快速开始

无需硬件，用 Python 校验协议实现：

```pwsh
git clone https://github.com/newMalloc/WatchPlay
cd WatchPlay
python tools/test_protocol.py        # 46 项一致性测试
python tools/gen_traces.py --check   # trace 与规范一致
python tools/lint_scenes.py          # 示例场景符合渲染规范
python tools/check_doc_links.py      # 文档交叉引用与锚点
```

Android 与手表端构建见 [快速开始指南](docs/guides/getting-started.md)。

## 路线图

| 阶段 | 目标 |
| :--- | :--- |
| 第一阶段 | 协议 v0.1，MJPEG 端到端 Demo，466×466 @ 10 fps，延迟 < 200 ms |
| 第二阶段 | 地图导航 + 音乐播放，H.264 支持，Wi-Fi 通道 |
| 第三阶段 | Android SDK 发布，协议 v1.0，兼容性测试套件，iOS 启动 |
| 第四阶段 | 厂商接入，WatchPlay Certified 认证 |

| 里程碑 | 交付物 | 状态 |
| :--- | :--- | :--- |
| M1 | MJPEG 投屏 Demo | 协议规范 v0.1 已完成 |
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

问题与 bug 请开 [Issue](https://github.com/newMalloc/WatchPlay/issues)。提交 PR 前请先读[贡献指南](CONTRIBUTING.md)。

## 许可证

[Apache License 2.0](LICENSE)
