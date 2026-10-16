# 贡献指南

感谢你有兴趣参与 WatchPlay。本文件说明如何提交被接受的贡献。

## 目录

- [在开始之前](#在开始之前)
- [开发环境](#开发环境)
- [协议变更](#协议变更)
- [代码规范](#代码规范)
- [提交与 PR](#提交与-pr)
- [测试要求](#测试要求)
- [许可证与署名](#许可证与署名)

## 在开始之前

WatchPlay 主要是协议标准，代码是它的参考实现。两点影响你怎么提改动：

- 协议改动比代码改动贵。第三方一旦按某个版本写了固件，改协议就要考虑他们。
- 所以需求先看能不能落在厂商私有空间（`0x80`–`0xFF`）或一个新的可选 TLV 里，不必动标准区。相关约束见 [07-capabilities.md](docs/spec/07-capabilities.md)。
- 参考实现的目标是证明协议可行，够用即可，不追求功能完备。

## 开发环境

### 通用

| 工具 | 版本 | 用途 |
| :--- | :--- | :--- |
| Python | 3.10+ | `tools/` 协议参考实现与测试（仅标准库，无第三方依赖） |
| Git | 2.30+ | |

```pwsh
python tools/test_protocol.py        # 协议一致性测试
python tools/gen_traces.py           # 重新生成 trace
python tools/gen_traces.py --check   # 校验 trace 与规范一致
```

### 手机端（`android/`）

| 工具 | 版本 |
| :--- | :--- |
| JDK | 17 |
| Android SDK | Platform 34、Build-Tools 34.0.0 |
| Gradle | 通过 wrapper（已提交，无需本地安装） |

需要设置 `ANDROID_HOME` 或 `android/local.properties` 中的 `sdk.dir`。`local.properties` 是**本地文件，禁止提交**。

```pwsh
cd android
./gradlew :sdk:assembleDebug
./gradlew :sdk:test
```

### 手表端（`watch/`）

| 工具 | 版本 |
| :--- | :--- |
| ESP-IDF | v5.1+ |
| 目标芯片 | esp32s3 |

```pwsh
cd watch
idf.py set-target esp32s3
idf.py build
idf.py -p COM<N> flash monitor
```

## 协议变更

改协议前先在 [docs/spec/07-capabilities.md](docs/spec/07-capabilities.md) 登记编号，避免和其他人的分配撞车。改动和这些一起提交：

- 规范文本
- [99-changelog.md](docs/spec/99-changelog.md) 的对应条目
- `tools/watchplay_proto.py` 的实现
- `tools/test_protocol.py` 的断言，以及 `examples/protocol-traces/` 里一条可解析的样例

不确定设计怎么定就先开 Issue 聊，不用先写代码——多数需求能落在厂商私有空间（`0x80`–`0xFF`）里先验证，不必动标准区。

设计约束（帧头布局冻结、厂商空间的使用方式、兼容性要求）都是规范内容，见 [07-capabilities.md](docs/spec/07-capabilities.md) 与 [02-wire-format.md §9](docs/spec/02-wire-format.md)。

## 代码规范

### 通用

- **缩进**：4 空格（JSON/YAML 为 2）。见 `.editorconfig`。
- **换行**：LF。**必须**是 UTF-8。
- **提交前**去掉行尾空格（Markdown 的双空格硬换行除外）。
- **注释解释"为什么"，不解释"是什么"**。代码已经说明了"是什么"。
- **不写"以防万一"的抽象**。协议实现要能被逐行读懂。

### Python（`tools/`）

- 仅使用标准库。`tools/` 必须能在裸 Python 3.10 上运行，因为它是协议的可执行参考。
- 所有公开函数有 docstring，并在适用时用 `>>>` 写出 doctest 风格的例子。
- 类型标注齐全。
- 规范里引用的每个数字 **应当** 能由 `tools/` 里的代码算出来，避免文档与实现漂移。

### Kotlin（`android/`）

- 官方 Kotlin 风格，4 空格缩进，最大行宽 120。
- 公开 API **必须**有 KDoc，说明契约与调用约束（线程、生命周期）。
- 不使用 Compose（`minSdk 26` 下参考实现保持依赖最小）。
- 阻塞调用（`glReadPixels`、`MediaCodec` 出队、BLE 写）**禁止**放在主线程或 UI 线程。
- **禁止**吞异常。每个 `catch` 要么处理，要么转成有信息量的异常再抛。

### C（`watch/`）

- ESP-IDF 风格，4 空格缩进。
- 所有跨核共享的数据结构 **必须**在注释里写明由哪个核拥有、用什么原语同步。
- 中断上下文里 **禁止** 调用非 `_from_isr` 后缀的 FreeRTOS API。
- 动态分配 **必须** 在会话建立时一次性完成。运行时的 `malloc`/`free` 在长时间运行下必然碎片化。

### 文档（`docs/`）

- 中文为主，技术术语保留英文。
- 规范性关键词用 `**必须（MUST）**` / `**禁止（MUST NOT）**` / `**应当（SHOULD）**` / `**可以（MAY）**`。
- **给出数字和公式，不给模糊建议**。写"帧率应降低"没用，写"帧率 −2，步长见 §6 表"才有用。
- **不编造实测数据**。估算必须标注为估算并写清假设。引用必须给可验证来源。
- 表格优先于长段落。

## 提交与 PR

### Commit message

使用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <subject>

<body>

<footer>
```

允许的 `type`：`feat`, `fix`, `docs`, `spec`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`

`scope` 建议：`spec`, `android`, `watch`, `tools`, `docs`, `demo`

示例：

```
spec(video): 明确 chunk_total 必须扣除 20 字节子头

原公式 ceil(frame_bytes / max_chunk_bytes) 会算出偏小的分块数，
接收端在第 N 块就认为帧结束，导致 JPEG 被截断。

补充了 8123 字节 @ 244 的完整算例（37 块而非 34 块），
并在 04-video.md §3.1 加入不变量校验表。

Refs: #12
```

### PR 要求

- 一个 PR 做一件事。协议改动与代码改动尽量分开提交，便于回溯。
- 描述里写清：改了什么、为什么、怎么验证的。
- 说明测试情况。没跑测试就写清没跑，以及原因。
- 涉及协议或共享接口的改动，一并更新对应文档。
- 保持 PR 小。超过 500 行的 PR 建议先开 Issue 讨论拆分。

## 测试要求

| 改动 | 最低验证要求 |
| :--- | :--- |
| `tools/watchplay_proto.py` | `python tools/test_protocol.py` 全绿 |
| `tools/gen_traces.py` | `python tools/gen_traces.py --check` 通过 |
| `docs/spec/` 中的字节布局 | 对应的 trace 可被解析，且 `--check` 通过（规范里引用的 hex 会被自动比对） |
| `docs/` 中的相对链接 | `python tools/check_doc_links.py` 通过 |
| `examples/watch-ui/` 中的场景 | `python tools/lint_scenes.py` 通过（触控目标、安全区单位、背景、外部资源） |
| `android/sdk` | `./gradlew :sdk:test` 通过 |
| `watch/` | `idf.py build` 通过；涉及协议的改动要附实测延迟/帧率 |

新增协议特性的 PR 需带一个不需要硬件就能跑通的测试，这样协议正确性可以由 CI 验证。

### 关于 `TestFrozenConstants`

不要为了提高通过率而放宽 `tools/test_protocol.py` 里的 `TestFrozenConstants`。线值一旦发布就可能已被固件实现，改动需要走[协议变更](#协议变更)并更新 [99-changelog.md](docs/spec/99-changelog.md)。

## 许可证与署名

- 所有贡献以 [Apache License 2.0](LICENSE) 授权。
- 提交 PR 即表示你同意你的贡献按 Apache 2.0 发布，并且你有权这样做。
- **禁止**提交来源不明的代码、逆向工程获得的厂商二进制、或与 Apache 2.0 不兼容的代码。
- 新增第三方依赖 **必须**在 PR 中说明许可证，且只允许 MIT / Apache-2.0 / BSD 系列。
