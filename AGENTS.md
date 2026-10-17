# AGENTS.md

WatchPlay 是手机 → 手表的投屏协议：手机用 HTML + CSS 渲染手表界面，WebView 捕获后编码为 MJPEG/H.264，经 BLE 送到手表显示，触控回传。本仓库 = 协议规范 + 参考实现。

## 权威顺序

`docs/spec/` 是协议权威。规范和代码冲突时按规范改代码，不改规范去迁就实现。规范里每个字节级断言都应该能由 `tools/` 里的代码或 trace 验证。

代码风格、提交格式、测试要求见 [CONTRIBUTING.md](CONTRIBUTING.md)，这里不重复。

## 布局

| 路径 | 内容 |
| :--- | :--- |
| `docs/spec/` | 协议规范，00–07 + 99-changelog |
| `docs/diagrams/` | 插图：Mermaid 源 `.mmd` + 渲染好的 `.svg` |
| `docs/guides/` | 使用指南 |
| `tools/` | 协议参考实现、测试、校验脚本（仅标准库） |
| `examples/` | 示例手表 UI（HTML + CSS）、字节级 trace |
| `android/sdk`、`android/demo` | 手机端 Kotlin SDK 与示例 App |
| `watch/docs/` | 手表端参考架构，目前只有文档，没有固件源码 |

## 跑什么

无第三方依赖，Python 3.10+ 即可：

```pwsh
python tools/test_protocol.py        # 46 项，末尾会顺带查文档链接
python tools/gen_traces.py --check   # 提交的 trace 与生成器是否一致
python tools/lint_scenes.py          # examples/watch-ui 是否符合渲染规范
python tools/check_doc_links.py      # 单独查相对链接与锚点
```

改了 `docs/diagrams/*.mmd` 之后：

```pwsh
python tools/render_diagrams.py      # 唯一需要联网的脚本
```

Android 端在 `android/`，`./gradlew :sdk:assembleDebug :sdk:test`，JDK 17 + SDK 34。

## 改动要连带的东西

- 协议字节：`watchplay_proto.py` + `test_protocol.py` 的断言 + `gen_traces.py` 重新生成 trace + `99-changelog.md` 条目。缺一样 CI 会挂。
- 改了 `.mmd` 没重渲染 SVG，提交的图就和源码不一致。CI 不查这个，只能靠人。
- `tools/` 里新增 import 要同步 `TestToolsAreStdlibOnly.ALLOWED`：它用 `ast` 看真实导入，不是正则匹配文本。
- `TestFrozenConstants` 冻结已发布的线值。它拦的是顺手改常量，不是障碍。

## 工程上的坑

- Android 用 AGP 9.1 的内置 Kotlin 支持。别加 `org.jetbrains.kotlin.android` 插件，它会挂在 `BaseExtension` 类型转换上。Gradle 走 wrapper 里的 9.3.1。`android/local.properties` 是本地文件，不提交。
- 分块公式是 `ceil(frame_bytes / (max_chunk_bytes - 20))`，不是 `ceil(frame_bytes / max_chunk_bytes)`。8123 字节 @ 244 是 37 块，34 是错的。
- 插图渲染时会把字体栈重写成 Arial/Helvetica。渲染服务端用 Liberation Sans 度量标签宽度，客户端若有 Trebuchet MS（宽约 18%）会把 `foreignObject` 里的文字裁掉。别把这段重写删了。
