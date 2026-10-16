# 05 — 输入回传（Input）

本文件定义手表触控回传 `TOUCH` 帧的字节布局、坐标映射、事件语义与失效处理。字节布局遵循 [02-wire-format.md](02-wire-format.md) 的 16 字节帧头约定；屏幕几何与安全区见 [06-rendering.md](06-rendering.md)。

## 1. `TOUCH` 负载（14 字节，定长）

| 偏移 | 长度 | 字段 | 类型 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| 0 | 1 | `action` | u8 | 见 §1.1 |
| 1 | 1 | `pointer_id` | u8 | 触点 ID，单点触控恒为 `0` |
| 2 | 2 | `x` | u16 | **手表物理显示坐标**，原点左上，单位像素 |
| 4 | 2 | `y` | u16 | 同上 |
| 6 | 1 | `pressure` | u8 | 压力归一化，`0`=不支持/无压力，`1`–`255` 递增 |
| 7 | 1 | `flags` | u8 | 见 §1.2 |
| 8 | 2 | `reserved` | u16 | **必须（MUST）** 为 0 |
| 10 | 4 | `device_ts_ms` | u32 | 手表单调时钟，毫秒，低 32 位 |

### 1.1 `action`

| 值 | 名称 | 说明 |
| :--- | :--- | :--- |
| `0x00` | `DOWN` | 手指按下（一个新触摸序列的开始） |
| `0x01` | `MOVE` | 手指移动（按下与抬起之间） |
| `0x02` | `UP` | 手指抬起（序列结束） |
| `0x03` | `CANCEL` | 序列被系统取消（如来电、面板重置、链路中断）。手机 **必须（MUST）** 视为 `UP` 并丢弃该手势的点击判定 |
| `0x04` | `LONG_PRESS` | 长按（手表侧判定，`>= 500 ms`）。**可以（MAY）** 不发，由手机侧自行从 `DOWN` 计时判定 |
| `0x05` | `SWIPE_START` | 滑动开始（可选，仅当手表实现了手势识别） |
| `0x06` | `SWIPE_END` | 滑动结束（可选） |

> **设计取舍**：`LONG_PRESS` / `SWIPE_*` 是**可选**的。协议更倾向于只回传原始 `DOWN`/`MOVE`/`UP`，让手机侧的 WebView 用标准 DOM 事件（`touchstart`/`touchmove`/`touchend`）处理手势。理由：① 手表的 MCU 上实现手势识别会占用 Core 0 的宝贵时间；② 浏览器的手势语义（点击阈值、滚动惯性、长按菜单）已经成熟，重复实现只会产生不一致。手表 **应当（SHOULD）** 只做最基本的去抖与坐标映射。

### 1.2 `flags`

| 位 | 掩码 | 名称 | 含义 |
| :--- | :--- | :--- | :--- |
| 0 | `0x01` | `COORDS_ROTATED` | 坐标已按 `CONTROL(ROTATE)` 的角度转换到显示坐标系 |
| 1 | `0x02` | `OUTSIDE_CIRCLE` | 落点在圆屏内接圆之外（手表 **应当（SHOULD）** 直接丢弃而不上报，此位用于调试） |
| 2 | `0x04` | `NEAR_EDGE` | 落点距屏边 < 5%，UI **可以（MAY）** 做补偿（如外扩命中区） |
| 3–7 | — | *保留* | 必须为 0 |

## 2. 坐标约定

### 2.1 必须是原始物理坐标

`x`/`y` **必须（MUST）** 是**手表物理显示像素**坐标，原点（`0,0`）在**左上角**，`x` 向右、`y` 向下。

**禁止（MUST NOT）** 在手表侧做任何缩放、安全区补偿或圆心归一化。理由：坐标映射依赖渲染时的实际布局（`CAPS_ACK` 的分辨率、`safe_inset_permille`、WebView 的 `devicePixelRatio`、页面的滚动位置），这些只有手机侧知道。手表做任何"聪明的"变换都会与手机的布局脱节。

### 2.2 圆屏的越界判定

圆屏上落在内接圆之外的点是**无效触摸**（面板边缘常有误触，且视觉上那里没有内容）。

给定屏宽 `W`、屏高 `H`（466×466），圆心与半径：

```
cx = (W - 1) / 2 = 232.5
cy = (H - 1) / 2 = 232.5
r  = min(W, H) / 2 = 233
```

落点在内接圆内的判定：

```
dx = x - cx
dy = y - cy
inside = (dx*dx + dy*dy) <= r*r
```

**手表侧：**

- 判定为圆外时，**应当（SHOULD）** 直接丢弃该点，**禁止（MUST NOT）** 上报为有效触摸。
- 若因调试需要上报，**必须（MUST）** 置 `OUTSIDE_CIRCLE`。
- 对于 `MOVE` 事件：手指从圆内滑到圆外时 **禁止（MUST NOT）** 直接发 `UP`；**应当（SHOULD）** 继续上报 `MOVE` 并把坐标**钳制**到圆上，或上报 `CANCEL`。直接发 `UP` 会让用户的拖动被误判为"松手"，破坏滑动操作。
- **应当（SHOULD）** 在圆边界内保留 **2 px** 的滞回（hysteresis），避免手指停在边界上时产生 `DOWN`/`UP` 抖动。

**手机侧：**

- **必须（MUST）** 对 `x >= W` 或 `y >= H` 的坐标做钳制，**禁止** 直接使用（WebView 收到越界坐标会触发意料外的元素命中）。
- 收到 `OUTSIDE_CIRCLE` 的 `DOWN` 时 **应当（SHOULD）** 忽略整个触摸序列，直到下一个 `DOWN`。

### 2.3 手机侧映射到 WebView CSS 像素

手机把物理坐标映射为 WebView 视口内的 CSS 像素坐标：

```
scale_x = css_viewport_width  / negotiated_width
scale_y = css_viewport_height / negotiated_height
css_x   = clamp(x * scale_x, 0, css_viewport_width  - 1)
css_y   = clamp(y * scale_y, 0, css_viewport_height - 1)
```

其中 `negotiated_width/height` 来自 `CAPS_ACK.RESULT`（即编码分辨率，v0.1 恒等于面板分辨率）。

**常见情形是 1:1**：WebView 以 `466 × 466` CSS 像素渲染、`devicePixelRatio = 1`、无滚动、无缩放。此时 `scale_x = scale_y = 1`，映射是恒等变换。实现 **应当（SHOULD）** 走这条快路径，并在检测到 `devicePixelRatio != 1` 或页面存在滚动偏移时才对完整公式求值。

**必须（MUST）** 补偿的因素：

| 因素 | 补偿 |
| :--- | :--- |
| 页面滚动 | 加上 `scrollX` / `scrollY` |
| 页面缩放 | 除以 `visualViewport.scale` |
| `devicePixelRatio` | `css = phys / dpr`（当 WebView 以设备像素渲染时） |
| `CONTROL(ROTATE)` | 按角度对坐标做旋转，再映射 |

> **强烈建议**：手表场景 **应当（SHOULD）** 使用 `overflow: hidden`、禁用缩放与滚动（见 [06-rendering.md](06-rendering.md) 的 CSS 契约）。这样上表的四项补偿全部退化为恒等，映射逻辑从"容易出错"变成"一行乘法"。这是协议层面鼓励简单实现的一个刻意选择。

### 2.4 合成 DOM 事件

手机侧 **应当（SHOULD）** 把 `TOUCH` 帧合成 WebView 的原生触摸事件（`dispatchTouchEvent` / Chromium 的 `WebInputEvent`），而**不是**注入合成 JS 事件（`new TouchEvent(...)`）。原因：

- 原生事件路径走的是浏览器自己的手势识别、滚动、`:active` 样式、点击延迟消除逻辑。
- 合成 JS 事件是**不可信事件**（`isTrusted === false`），无法触发某些默认行为（如 `<input>` 聚焦、`<video>` 播放，`autoplay` 策略），且 `preventDefault()` 行为不一致。

参考实现中，`TouchHandler` 通过 `WebView.dispatchTouchEvent(MotionEvent.obtain(...))` 注入，`MotionEvent` 的 `source` 设为 `SOURCE_TOUCHSCREEN`，并正确设置 `action`、`pointerId`、`eventTime`。

**时间戳**：`MotionEvent` 的 `eventTime` **必须（MUST）** 基于 `SystemClock.uptimeMillis()`，且 **应当（SHOULD）** 用 `device_ts_ms` 校正链路延迟。若手表时钟不可靠（无 RTC），**应当（SHOULD）** 退化为"收到即用当前 `uptimeMillis()`"，并接受约 `RTT/2` 的抖动。

## 3. 事件序列规则

**必须（MUST）** 遵守的序列约束：

| 规则 | 说明 |
| :--- | :--- |
| `DOWN` 开启序列 | 一个 `pointer_id` 的序列 **必须** 以 `DOWN` 开始 |
| `UP`/`CANCEL` 结束序列 | 结束后 **必须** 有新的 `DOWN` 才能继续 |
| `MOVE` 只能在 `DOWN` 之后 | 未见过 `DOWN` 的 `MOVE` **必须（MUST）** 被手机丢弃 |
| 同时多点触控 | 不同 `pointer_id` 的序列 **可以（MAY）** 交错；v0.1 参考实现只支持单点 |
| 丢包容忍 | `MOVE` 是**幂等**的，丢失无影响；`DOWN`/`UP` **必须（MUST）** 可靠 |

### 3.1 `DOWN`/`UP` 的可靠性

`TOUCH` 帧 **应当（SHOULD）** 只在**有人触摸时**发送，因此单个 `DOWN`/`UP` 的丢失会直接导致 UI 永久卡在"按下"状态。

保护机制：

1. **手表侧超时兜底**：若发出 `DOWN` 后 **5 秒** 内未发出 `UP`（例如手指离开面板但中断事件丢失），手表 **必须（MUST）** 主动补发一个 `UP`。
2. **手机侧超时兜底**：收到 `DOWN` 后 **10 秒** 内未收到 `UP`/`CANCEL`，手机 **必须（MUST）** 合成一个 `ACTION_CANCEL` 给 WebView，并记录 `stuckTouchRecoveries += 1`。
3. **链路断开**：任一端检测到断开时，**必须（MUST）** 丢弃所有未完成的触摸序列（手机侧合成 `ACTION_CANCEL`）。
4. **`DOWN` 上报重试**：手表 **可以（MAY）** 对 `DOWN` 置 `ACK_REQUIRED` 并在 200 ms 内未收到 `CONTROL(ACK)` 时重传一次（`RETRANSMIT` 置位、复用同一 `seq`）。**禁止（MUST NOT）** 对 `MOVE` 这样做。

> v0.1 参考实现选择"不重传、靠超时兜底"，因为它实现简单且失败模式可见（UI 卡住 10 秒后自愈）。重传是 v0.2 的改进方向。

## 4. 采样与节流

| 参数 | 建议值 | 说明 |
| :--- | :--- | :--- |
| 手表采样率 | 跟随触控 IC（FT3168: 60–100 Hz） | |
| 上报节流 | **≤ 30 Hz**（33 ms） | 更高的上报率在 10 fps 的显示刷新下不可见，只会浪费 BLE 带宽 |
| 去抖 | `DOWN` 前 30 ms 稳定 | 防误触 |
| 最小移动距离 | 2 px 才上报 `MOVE` | 抑制噪声 |
| 合并上报 | **应当（SHOULD）** 把节流窗口内最新的 `MOVE` 合并为一个 | 与 `MOVE` 的幂等性一致 |

**带宽预算**：`TOUCH` 帧线上 30 字节（16 头 + 14 负载）。@30 Hz 单点触控约 **900 B/s**，相对于 Profile A 的 150–300 KB/s 可忽略（< 0.6%），但相对于 BLE 的连接事件配额并非可忽略——因此仍 **必须（MUST）** 节流。

> **与 Credit 的关系**：`TOUCH` 是接收端的反向流量，**不消耗** Credit。Credit 只约束发送端（手机）的 `VIDEO` 流量。方向独立，不要混淆。

## 5. 触控映射的边界情形

| 情形 | 期望行为 |
| :--- | :--- |
| 圆屏边缘 5% 环带内的 `DOWN` | 上报并置 `NEAR_EDGE`；UI **可以（MAY）** 把命中区向外扩，但 **禁止** 让相邻控件的命中区重叠 |
| 快速滑动（> 1000 px/s） | 手表采样可能丢点，手机侧 **应当（SHOULD）** 用上一次 `MOVE` 做线性插值补点，避免滑动轨迹断裂 |
| 触摸期间收到 `CONTROL(ROTATE)` | 手机 **必须（MUST）** 丢弃当前序列（合成 `CANCEL`），因为坐标系已改变 |
| 触摸期间 UI 场景切换 | 同手机行为：丢弃当前序列 |
| 手指按住不动 | **禁止（MUST NOT）** 持续发送 `MOVE`（无移动即无事件）；长按由手机侧计时 |
| 多点触控超出支持数 | 手表 **应当（SHOULD）** 忽略超出 `CAPS.INPUT.touch_points` 的触点 |

## 6. 时间同步

`device_ts_ms` 是**手表单调时钟**的低 32 位，**不是** Unix 时间，双方时钟不同源。

- 它用于**手势内部**的相对时序（如区分轻点与长按、判断滑动速度），此时单一时钟源的差值是正确的。
- **禁止（MUST NOT）** 用它计算端到端延迟（跨时钟域）——那需要 [03-session.md §6](03-session.md) 的 `PING`/`PONG` 偏移估计。
- 手表回绕 u32（约 49.7 天）时 **应当（SHOULD）** 在差值计算中使用模运算，而非直接相减。

## 7. 无触控设备

若 `HELLO.flags` 未置 `HAS_TOUCH`：

- 手机 **禁止（MUST NOT）** 期待任何 `TOUCH` 帧，**必须（MUST）** 在 UI 中隐藏或禁用需要交互的元素。
- 手表 **禁止（MUST NOT）** 发送 `TOUCH` 帧。
- 手机 **可以（MAY）** 提供替代输入通道（如手机端自身的触控板映射到手表坐标）。

## 8. 一致性检查清单

- [ ] `TOUCH` 负载恒为 14 字节
- [ ] 坐标为手表物理像素、原点左上、无任何手表侧缩放
- [ ] 圆屏越界点被丢弃，或置 `OUTSIDE_CIRCLE`
- [ ] `MOVE` 滑出圆外不产生 `UP`，而是钳制或 `CANCEL`
- [ ] `DOWN` 无对应 `MOVE` 前导时被手机丢弃
- [ ] 手表 5 s 兜底补发 `UP`
- [ ] 手机 10 s 兜底合成 `ACTION_CANCEL`
- [ ] 断链时丢弃所有未完成触摸序列
- [ ] 上报率 ≤ 30 Hz，`MOVE` 有 2 px 阈值
- [ ] 单次触摸按住不动时不发 `MOVE`
- [ ] 用原生 `MotionEvent` 注入而非合成 JS 事件
- [ ] 坐标映射补偿了滚动/缩放/`devicePixelRatio`/旋转（或证明它们均为恒等）
- [ ] `TOUCH` 不消耗 `VIDEO` Credit
