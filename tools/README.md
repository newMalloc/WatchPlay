# tools/ — 协议参考实现与检查工具

本目录是 WatchPlay 协议的**可执行参考实现**。

它不是"顺手写的脚本"，而是规范的一部分：`docs/spec/` 里每一个字节级断言，都应该能在这里找到对应的代码或测试。如果规范和代码打架，**规范赢，代码是 bug**——但这同时意味着规范里的每个数字都必须是可验证的。

`tools/` 只使用 Python 标准库，不装任何依赖就能跑。这条约束由 `test_protocol.py` 中的 `TestToolsAreStdlibOnly` 检查（用 `ast` 解析真实导入，而不是正则匹配文本）。

## 文件

| 文件 | 作用 |
| :--- | :--- |
| [`watchplay_proto.py`](watchplay_proto.py) | 线格式的权威实现：帧头编解码、CRC-16、RFC 1982 序列号算术、流式解析器（含重同步）。**这是规范的可执行形式。** |
| [`gen_traces.py`](gen_traces.py) | 生成 `examples/protocol-traces/` 的权威字节级 trace。规范里引用的每个 hex dump 都由它产出，所以文档不可能悄悄漂移。 |
| [`analyze_stream.py`](analyze_stream.py) | 抓包分析器：帧类型直方图、CRC 错误、重同步、**off-by-20 分块诊断**、Credit 流、实测帧率与开销。 |
| [`test_protocol.py`](test_protocol.py) | 41 项一致性测试，含"冻结常量"与示例场景校验。 |
| [`lint_scenes.py`](lint_scenes.py) | 校验 `examples/watch-ui/` 的场景符合渲染规范：触控目标 ≥ 7 mm、安全区用 `vmin`、背景不透明、无外部资源、无滚动。 |
| [`check_doc_links.py`](check_doc_links.py) | 校验 `docs/` 里的相对链接与页内锚点。 |

## 常用命令

```pwsh
python tools/test_protocol.py          # 全部一致性测试（41 项）
python tools/gen_traces.py             # 重新生成 trace（改动协议后必须跑）
python tools/gen_traces.py --check     # 校验提交的 trace 与当前代码一致（CI 用）
python tools/check_doc_links.py        # 校验文档交叉引用
python tools/lint_scenes.py            # 校验示例场景符合渲染规范
python tools/analyze_stream.py <file>  # 分析抓包
```

## 用它验证你自己的实现

这是本目录最重要的用途。你写手表端固件或另一套手机端 SDK 时，不需要先有硬件就能验证协议正确性：

### 1. 校验你的帧头

```python
from tools.watchplay_proto import crc16_ccitt_false, encode, FrameType

# 你的实现必须给出同样的 CRC
assert crc16_ccitt_false(b"123456789") == 0x29B1    # 标准校验值

# 生成一个你能拿去对比的帧
frame = encode(FrameType.PING, seq=1)
print(frame.hex(" "))
# 57 50 01 0a 00 00 10 00 01 00 00 00 00 00 8c e0
```

### 2. 用它做黄金样本

`examples/protocol-traces/mjpeg-frame-466x466-3c.bin` 是**一个完整 MJPEG 帧在线上应有的全部字节**。让你的实现解析它，检查：

- 解析出 **37** 个 `VIDEO` 帧（不是 34）
- 零 CRC 错误、零重同步
- 重组出的媒体数据 **8123 字节**
- 每块 `payload_len` ≤ 244，除最后一块外都等于 244

任何一条不符，你的实现就有 bug。

### 3. 分析你的抓包

```pwsh
python tools/analyze_stream.py your_capture.bin
```

它会自动做分块数自洽性检查。如果你犯了 off-by-20，输出会直接告诉你是哪一步算错了：

```
chunking check:
  media bytes/chunk   224  (payload_len - 20)
  chunk_bytes         244  (max_chunk_bytes)
  reported total      34
  correct total       37   = ceil(frame_bytes / (chunk_bytes - 20))
  !! MISMATCH: reported 34, expected 37
     a sender computing ceil(8123 / 244) would report 34 -- the classic off-by-20 bug
```

支持的输入格式：裸字节流（`.bin`/`.raw`）、十六进制文本（`.hex`/`.txt`）、经典 pcap（`.pcap`，DLT 256 BLE LL）。

> ⚠️ **Android 的 `btsnoop_hci.log` 不能用。** 它只记录 HCI 命令与事件，GATT 通知的 ATT 负载**不在其中**。要得到可分析的字节，用 `pcap`（如 nRF Connect 的 BLE 抓包、或 Android 的 HCI snoop 配合 btmon 重建 L2CAP），或者在你自己的实现里把协议层字节 dump 到文件。工具不接受 btsnoop 不是缺陷，是因为那里确实没有数据可解析。

### 4. 作为交叉实现

如果你的实现是 C 或 Kotlin，可以在单元测试里把 `watchplay_proto.py` 当作**对端**：让 C 端编码、Python 端解码，反之亦然。两个独立实现能互相解析，是"协议定义无歧义"的最好证据。

## 修改规范时必须同步的事

改了 `docs/spec/` 里任何字节级的东西：

1. 改 `watchplay_proto.py` 的常量或逻辑
2. 跑 `python tools/gen_traces.py` 重新生成 trace
3. 跑 `python tools/test_protocol.py` —— `TestFrozenConstants` 会拦住静默改线值的操作
4. 若改了取值的含义，更新 `docs/spec/99-changelog.md`

线值一旦发布就不能随意更改：可能已有固件按它实现。`TestFrozenConstants` 就是为此存在。
