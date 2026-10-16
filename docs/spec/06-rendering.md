# 06 — 渲染、设计坐标系与安全区

> 状态：**Draft**（协议版本 `0.1`）
> 规范性关键词（**必须（MUST）** / **禁止（MUST NOT）** / **应当（SHOULD）** / **可以（MAY）**）含义见 [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119)。
> 本文只定义**场景侧**的渲染契约（设计坐标系、安全区、CSS 契约、圆/方屏适配）。屏幕几何的**线格式**（`CAPS` / `CAPS_ACK` 的字段与能力位）见 [07-capabilities.md](07-capabilities.md)；触控帧的字节布局见 [05-input.md](05-input.md)。本文引用它们，不重新定义任何二进制布局。

## 1. 设计坐标系（Design Coordinate System）

### 1.1 设计视口与 192 dp 基准

一个**场景（Scene）**是一份自包含的 HTML+CSS 文档，由手机 WebView 渲染成**恰好等于手表物理分辨率**的画面（参考硬件 466×466）。场景作者面对的抽象画布称为**设计视口**：

| 属性 | 值 |
| :--- | :--- |
| 设计视口短边 | **192 设计单位（du）** |
| 设计视口形状 | 正方形（圆屏与方屏共用同一画布） |
| 屏幕外接方框短边 | `S` 物理像素（圆屏 `S` = 直径；非正方形屏 `S = min(W, H)`） |
| 尺寸单位 | **禁止**绝对像素；**必须**用 `%` / `vmin` / `vw` / `vh`，或其相对派生（`em` / `rem`） |

192 dp 是**项目约定**的创作基准，不是任何硬件规格。它的作用是固定一组可复用的数值（例如"16 du 的边距"），并给设计师一个手机 dp 量级的心智模型。

### 1.2 缩放因子，以及为什么**不**把它写进 CSS

```
k = S / 192 = 466 / 192 = 2.4270833…      （192 × 2 = 384，余 82；82/192 = 0.4270833…）
```

| 设计单位 | 466 px 下 | 占短边 | 设计单位 | 466 px 下 | 占短边 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 8 du | 19.42 px | 4.167% | 48 du | 116.50 px | 25.000% |
| 16 du | 38.83 px | 8.333% | 96 du | 233.00 px | 50.000% |
| 26.88 du | 65.24 px | 14.000% | 135.76 du | 329.51 px | 70.711% |
| 28.12 du | 68.24 px | 14.645% | 192 du | 466.00 px | 100.000% |

**禁止（MUST NOT）** 把 `k = 2.427` 或任何绝对像素值烘焙进场景 CSS。理由：

1. **同一份文档要跑在多种屏幕上**——圆屏、方屏、未来的 240×240 / 454×454 必须复用同一场景（§5）。
2. **烘焙 `2.427` 等于把 466 写死**，渲染分辨率一旦变化（例如低带宽模式下手机以 0.5× 渲染再上采样，见 [04-video.md](04-video.md)）场景立刻失效。
3. 百分比让渲染引擎在布局阶段解析尺寸，不产生额外重排；用 `transform: scale()` 做整体缩放会引入非整数像素采样，文字发虚。

### 1.3 设计单位 → 百分比的换算公式

以短边 `S` 为 100%，长度 `d`（设计单位）对应的百分比：

```
p(d) = d / 192 × 100%  =  d × 0.520833…%        （1/192 = 0.0052083…）
d(p) = p / 0.520833    =  p × 1.92              （反算，用于对照设计稿）
```

例：安全区内边距 14% → `14 / 0.520833 = 26.88 du`；16 du 间距 → `16 × 0.520833 = 8.333%`。

### 1.4 为什么用 `vmin`

| 单位 | 定义 | 圆屏上的问题 |
| :--- | :--- | :--- |
| `vw` / `vh` | 视口宽 / 高的 1% | 屏幕变宽或变高时尺寸跟着变，破坏圆屏的各向同性 |
| **`vmin`** | **`min(vw, vh)`，即短边的 1%** | 无 |
| `%`（作为 `padding`/`margin`） | 包含块**宽度**的 1% | 四个方向永远按宽度解析，非正方形屏上会算错 |

**必须（MUST）** 在圆屏场景中优先用 `vmin`：`1vmin == 1% S`，因此 §2.3 的 `safe_inset_permille` 除以 10 就**直接等于**以 `vmin` 为单位的内边距，无需换算表。466×466 上 `1vmin = 4.66 px`；240×240 上 `2.40 px`；410×502 上 `4.10 px`（跟随短边）——场景代码一字不改。

> **百分比 `padding` 陷阱**：`padding: 14%` 在正方形屏上"碰巧正确"，但它解析的是包含块**宽度**的 14%。安全区内边距 **必须（MUST）** 写成 `padding: var(--wp-safe-inset)`，且该变量是 `vmin` 值。

### 1.5 可选的作者便利：`1rem == 1 设计单位`

```css
html{ font-size: .520833vmin; }   /* 100vmin / 192 = 0.520833vmin */
```

这样 `16rem == 16 du == 8.333% S`，设计师可以直接把设计稿数字填进 CSS。这是 **可以（MAY）** 的便利写法，不是规范要求：部分 WebView 的 `minimumLogicalFontSize`（默认常见为 8）会把这个 2.4 px 的根字号顶上去，从而破坏基准。采用该写法时手机端 **应当（SHOULD）** 显式 `settings.minimumLogicalFontSize = 1`（§3.2）。§6 的示例场景不依赖该技巧。

## 2. 安全区（Safe Area）

### 2.1 圆屏几何：内接正方形（硬边界）

设圆屏短边（= 直径）`S`，半径 `R = S/2`。考虑**居中、轴对齐**的正方形，边长 `a`，半边长 `h = a/2`。角点到圆心的距离是 `h√2`（45° 直角三角形）。要求角点不超出圆：

```
h·√2 ≤ R = S/2   ⇒   h ≤ S/(2√2)   ⇒   a = 2h ≤ S/√2 = 0.7071068·S

inset = (S − S/√2)/2 = S · (1 − 0.7071068)/2 = S · 0.2928932/2 = 0.1464466·S
      = 14.6447% = 146.447‰
```

466 px 上：`inset = 0.1464466 × 466 = 68.24 px`，内接正方形边长 `466/√2 = 329.51 px`（校验 `466 − 2×68.24 = 329.52` ✓）。

**14.645% 是几何硬极限**：任何居中轴对齐的矩形，内边距小于该值就一定有像素落在圆外。这正是项目级"圆屏内边距 10%–14%"的由来——它必须严格小于 14.645% 才有意义。

### 2.2 内接正方形 ≠ 舒适安全区

| 概念 | 数值（‰ 短边） | 性质 | 用途 |
| :--- | ---: | :--- | :--- |
| 屏幕边缘 | 0 | 物理边界 | — |
| **内接正方形** | **146.4** | 纯几何硬极限 | 极限参考；**信息内容禁止**贴到这个边界 |
| **推荐安全区** | **100–146**（默认 **140**） | 工程建议 | 文本、图标、触控目标的容器 |
| 方屏推荐 | 25–50（默认 **40**） | 工程建议 | 方屏的可见性几乎不受形状限制，内边距主要用于观感与面板边框 |
| 中央关键内容带 | 150 | 工程建议（圆屏） | `\|y\| ≤ 0.35·S` 的正文带，见 §2.6 / §3.5.3 |

三者是**递进收紧**的关系：内接正方形只能保证"不被几何裁切"，一个恰好贴边的正方形，四个角正好落在圆周上——视觉上文字**看起来**仍被弧线压住。推荐安全区在硬极限内再留 6.4‰ ≈ 3 px（466 px 上）的呼吸空间。**装饰性全出血**素材 **可以（MAY）** 超出安全区甚至铺满整个 `S × S` 外接方框；只有**信息**不允许被裁切。

### 2.3 `safe_inset_permille` 的精确定义

手表在 `CAPS` 中上报 `safe_inset_permille`（二进制布局见 [07-capabilities.md](07-capabilities.md)）。它的语义**精确地**是：

> **接收端建议发送端在场景四周保留的内边距，单位是屏幕短边的千分之一（‰）。**

| 编号 | 要求 |
| :--- | :--- |
| R1 | 手机 **必须（MUST）** 把收到的值**原样**注入场景：`--wp-safe-inset: (safe_inset_permille / 10)vmin`（例 `140 → 14vmin`）。 |
| R2 | 手机 **禁止（MUST NOT）** 用自己硬编码的经验值（如"圆屏一律 14%"）替换上报值。只有手表知道自己的面板边框、圆角半径与实际可视区。 |
| R3 | 手机 **应当（SHOULD）** 在**首帧渲染之前**完成注入（§3.2），避免首帧以错误边距呈现。 |

- 有效范围 `0 … 499`。`500` 会让安全区退化为空（`0.5 + 0.5 = 1.0`），**禁止**接受；`> 499` 的手机端 **应当（SHOULD）** 钳制到 499 并记录告警。
- `== 0` 或字段缺失（老固件）时，手机 **应当（SHOULD）** 使用 §2.4 的默认值。
- 场景 **禁止（MUST NOT）** 假设内边距一定是 14%。它可能是 0（方屏）也可能是 400（异形屏）；场景 **应当（SHOULD）** 在 ≥ 200‰ 时优雅降级（隐藏装饰、缩小主视觉、减少列表项）。

### 2.4 默认值表

| 屏幕（短边） | 形状 | 有效区间（‰） | 默认上报值（‰） | 注入为 | 折算内边距 |
| :--- | :--- | ---: | ---: | :--- | ---: |
| 466×466（LilyGO T-Display S3 AMOLED 1.43"） | ROUND | 100–146 | **140** | `14vmin` | 65.2 px |
| 466×466 | SQUARE | 25–50 | **40** | `4vmin` | 18.6 px |
| 454×454（圆屏） | ROUND | 100–146 | 140 | `14vmin` | 63.6 px |
| 240×240（圆屏） | ROUND | 100–146 | 140 | `14vmin` | 33.6 px |
| 410×502（矩形圆角，假想） | SQUARE | 25–50 | 40 | `4vmin` | 16.4 px（短边 410） |

> **140‰ 的选择依据**：硬极限 146.4‰ 减去约 6.4‰ 余量。取 146‰ 会把每个角点压在圆周上（视觉上已被弧线切到），取 100‰ 又浪费可用面积。140‰ 在"不裁切"与"少浪费"之间取了一个保守但可用的点。**这是工程取值（estimate），不是测量结果。**

### 2.5 示意图

```
       圆屏（S = 466 px）与其安全区，数值为占 S 的百分比 / 466 px 下的像素
  0%       14%      14.645%              85.355%   86%      100%
   ┌────────┬─────────┬──────────────────────┬───────┬────────┐
   │   ╭────┴─────────┴──────────────────────┴───────┴────╮   │
   │ ╭─╯   ┌───────────────────────────────────────────┐  ╰─╮ │
   │╱      │ ① 内接正方形（硬极限）                      │    ╲│
   ││      │    side = S/√2 = 0.70711·S = 329.5 px      │     ││
   ││      │    inset = 14.645% = 146.4‰ = 68.2 px      │     ││
   ││      │  ┌─────────────────────────────────────┐   │     ││
   ││      │  │ ② 推荐安全区（默认）                  │   │     ││
   ││      │  │    140‰ = 14% = 65.2 px             │   │     ││
   ││      │  │    screen y ∈ [14%, 86%]            │   │     ││
   ││      │  │  ┌───────────────────────────────┐  │   │     ││
   ││      │  │  │ ③ 中央 70% 关键内容带（正文）   │  │   │     ││
   ││      │  │  │    screen y ∈ [15%, 85%]      │  │   │     ││
   ││      │  │  │    |y| ≤ 0.35·S，宽 ≤ 63.4% S  │  │   │     ││
   ││      │  │  └───────────────────────────────┘  │   │     ││
   ││      │  └─────────────────────────────────────┘   │     ││
   │╲      └───────────────────────────────────────────┘    ╱│
   │ ╰─╮                                                 ╭─╯ │
   │   ╰─────────────────────────────────────────────────╯   │
   └─────────────────────────────────────────────────────────┘
```

边长推导（四分之一象限）：半边长 `h`，角点到圆心距离 `h·√2`，要求 `h·√2 ≤ R = S/2` ⇒ `a = 2h ≤ S/√2 ≈ 0.70711·S` ⇒ `inset = (S − a)/2 = 0.146447·S`。

### 2.6 圆屏上任意垂直位置的可用宽度（弦长）

设某行内容中心距圆心的垂直距离为 `y`（单位与 `S` 相同），水平安全余量 `m = 0.04·S`（左右各一份）：

```
弦长      chord(y) = 2√(R² − y²) = √(S² − 4y²)
宽度上限  w_max(y) = √(S² − 4y²) − 2m = ( √(1 − 4·(y/S)²) − 0.08 ) × 100%
```

在 `S = 466` 上：

| 屏幕 y（%） | `\|y\|/S` | `√(1−4t²)` | `w_max` | px | 备注 |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 50（圆心） | 0.00 | 1.0000 | 92.0% | 428.7 | 最宽 |
| 40 | 0.10 | 0.9798 | 90.0% | 419.4 | |
| 35 | 0.15 | 0.9539 | 87.4% | 407.3 | |
| 30 | 0.20 | 0.9165 | 83.7% | 390.0 | |
| 25 | 0.25 | 0.8660 | 78.6% | 366.3 | |
| 20 | 0.30 | 0.8000 | 72.0% | 335.5 | 中央 70% 带边缘 |
| **18 / 82** | **0.320** | 0.7684 | **68.8%** | **320.8** | 3 个 7 mm 目标 + 2×2 mm 间距的临界位置（§3.6.3） |
| **15 / 85** | **0.35** | 0.7141 | **63.4%** | **295.5** | 中央 70% 带最不利宽度 |
| 10 | 0.40 | 0.6000 | 52.0% | 242.3 | 已非常窄 |
| 5 | 0.45 | 0.4359 | 35.6% | 165.9 | 几乎不可用 |
| 0 / 100 | 0.50 | 0 | −8.0% | 0 | 圆周上无水平空间 |

**简化规则（推荐默认）**：正文文本块 **应当（SHOULD）** 收在以屏幕中心为中心、边长 `0.70·S` 的居中正方形内（圆屏默认即 screen y ∈ [15%, 85%]）。该带内 `w_max ≥ 63.4%`，因此取 **正文宽度 ≤ 63% S** 即可在带内任意位置安全放置；需要"用满弦长"时再逐行取精确值。

## 3. CSS 契约（CSS Contract）

### 3.1 不可协商的根设置

```css
*{ box-sizing: border-box; margin: 0; padding: 0; }

html, body{
  width: 100%; height: 100%;
  overflow: hidden;                 /* 必须：滚动条会改变视口宽度 → vmin 漂移 */
  overscroll-behavior: none;
  touch-action: none;               /* 禁止引擎自己的平移/缩放手势吃掉首次触控 */
  background: var(--wp-bg, #000);   /* 必须显式：透明背景在 JPEG 里会变黑（§7） */
  color: var(--wp-fg, #fff);
  font-family: system-ui, "Roboto", "Noto Sans CJK SC", "Source Han Sans SC", sans-serif;
  font-variant-numeric: tabular-nums;          /* 时间/进度数字不跳动 */
  -webkit-user-select: none; user-select: none;
  -webkit-touch-callout: none;
  -webkit-tap-highlight-color: transparent;    /* 关掉 WebView 的灰色点击高亮 */
  -webkit-text-size-adjust: 100%; text-size-adjust: 100%;
}
::-webkit-scrollbar{ width: 0; height: 0; display: none; }
```

| 设置 | 为什么是"必须" |
| :--- | :--- |
| `overflow: hidden` | 一旦出现滚动条，initial containing block 变窄，`1vmin` 随之变小，整套百分比布局整体漂移；捕获端也不会滚动。 |
| 显式非透明 `background` | JPEG 没有 alpha 通道，透明像素编码后是黑色，与作者在手机白色 WebView 底上看到的效果完全不同。 |
| `box-sizing: border-box` | 否则"`height: 100%` + `padding`"会撑出视口，直接触发溢出。 |
| `-webkit-tap-highlight-color: transparent` | 默认灰色高亮在 10 fps 捕获下会糊成色斑，且帧间不可控。 |
| `touch-action: none` | 否则 WebView 可能把一次拖拽判定为滚动，吞掉 `pointermove`。 |

### 3.2 手机端注入协商结果

```kotlin
// 1) 用 density == 1.0 的 Configuration 上下文创建 WebView：
//    保证 1 CSS px == 1 手表物理 px，从而使 1vmin == 1% 短边成立。
val cfg = Configuration(resources.configuration).apply { densityDpi = 160 } // DENSITY_DEFAULT
val web = WebView(createConfigurationContext(cfg))

// 2) 关掉一切会改写布局的浏览器行为
web.settings.apply {
    javaScriptEnabled = true
    loadWithOverviewMode = false      // 不要缩放到适合宽度
    useWideViewPort = false
    textZoom = 100                    // 禁止系统字体放大
    minimumFontSize = 1               // 禁止最小字号顶掉小字号
    minimumLogicalFontSize = 1        // 使用 rem 基准（§1.5）时必须
    setSupportZoom(false); builtInZoomControls = false
    blockNetworkLoads = true          // 场景必须完全自包含：禁止任何网络请求
    allowFileAccess = false; cacheMode = WebSettings.LOAD_NO_CACHE
}

// 3) 在 document-start 注入协商结果，保证首帧就带正确的安全区
val insetVmin = caps.safeInsetPermille / 10.0                    // 140 → 14.0
val shape = if (caps.screenShape == SHAPE_ROUND) "round" else "square"
val js = """
  document.documentElement.dataset.screenShape = '$shape';
  document.documentElement.style.setProperty('--wp-safe-inset', '${insetVmin}vmin');
""".trimIndent()
if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
    WebViewCompat.addDocumentStartJavaScript(web, js, setOf("*"))
} else {
    // 兜底：加载前把值字符串替换进 HTML 的 :root{}，同样零闪帧
    html = html.replace("/*WP_INJECT*/", "--wp-safe-inset:${insetVmin}vmin;")
}

// 4) 精确测量到手表物理分辨率（纹理尺寸 == CAPS.width × CAPS.height）
web.measure(View.MeasureSpec.makeMeasureSpec(caps.width,  View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(caps.height, View.MeasureSpec.EXACTLY))
web.layout(0, 0, caps.width, caps.height)
```

**关于非 1:1 的 device pixel ratio。** 所有边距都用相对单位，所以 DPR ≠ 1 时布局**看起来**仍然正确；但两件事会坏：① `1vmin` 不再等于 1% 的手表物理像素，捕获纹理尺寸与触控映射之间出现因子 `scale = 物理px / CSSpx`（§4）；② 文字先按 CSS px 网格栅格化再被放大，`border: 1px` 之类的细节会消失或变成 2 px。

因此手机 **必须（MUST）** 通过步骤 1 把 `scale` 钉在 1.0；做不到时（例如必须复用宿主 Activity 的 Context）**必须（MUST）** 把实际 `scale` 同时用于捕获缩放与触控逆映射（§4），并 **应当（SHOULD）** 避免场景中出现依赖 1 px 精度的元素（细描边、发丝分隔线改用 `0.2vmin` 起步）。

### 3.3 `.safe-area` 容器

```css
:root{
  --wp-safe-inset: 14vmin;            /* 兜底默认值；手机端在 document-start 覆盖（§3.2） */
}
html[data-screen-shape="square"]{ --wp-safe-inset: 4vmin; }

.safe-area{
  height: 100%;                       /* body 有确定高度，所以百分比成立 */
  padding: var(--wp-safe-inset);      /* 必须 vmin；禁止 padding: 14%（§1.4） */
  display: flex; flex-direction: column; align-items: center;
}
.safe-area > *{ max-width: 100%; box-sizing: border-box; }
```

> **绝对定位子元素的包含块是祖先的 padding box（含 padding 区域）**，不是 content box。因此不要在一个带 padding 的 `.safe-area` 里直接写 `top: 0` 并期望它落在安全区顶部；需要按安全区坐标精确定位时，再加一层 `position: relative; height: 100%` 的 content-box 包装。等价的绝对定位写法是 `position: absolute; top/right/bottom/left: 0;` + 同一个 `padding`。

### 3.4 单场景适配圆屏与方屏

**必须（MUST）** 用**一份**场景文档、通过 `<html>` 上的形状开关适配两种屏幕，**禁止**维护两份文档：两份文档必然分叉，且手机端要按协商结果切换 URL/内容，会多出一帧的加载抖动。

```css
/* 形状开关由手机端写入（§3.2）；默认 round，避免属性缺失时布局崩掉 */
html[data-screen-shape="round"]  { --wp-safe-inset: 14vmin; }
html[data-screen-shape="square"] { --wp-safe-inset: 4vmin; }

/* 圆屏：正文宽度 ≤ 88% 安全区宽（88% × 72% = 63.4% S）；方屏可以用满 */
html[data-screen-shape="round"]  .text { max-width: 88%; }
html[data-screen-shape="square"] .text { max-width: 100%; }

html[data-screen-shape="round"]  .square-only { display: none; }
html[data-screen-shape="square"] .round-only  { display: none; }
html[data-screen-shape="round"]  .dial { width: 26vmin; height: 26vmin; }
html[data-screen-shape="square"] .dial { width: 34vmin; height: 34vmin; }
```

**应当（SHOULD）** 只用 CSS 做形状适配，不要在 JS 里 `if (shape === 'round')` 重建 DOM——重建触发完整重排 + 重绘，在 10 fps 的捕获节拍下很可能丢帧。需要 JS 分支时只切换类名 / 属性。

### 3.5 文字

#### 3.5.1 最小可读字号（推导）

假设：观看距离 `d = 300 mm`（抬腕一瞥的典型距离，**估计值**）；最小可分辨笔画宽度取 **1.5 弧分**（1 弧分是标准视力 20/20 的分辨极限，1.5× 作为 AMOLED 低对比度与相对运动的余量）。常规无衬线字体的笔画宽度约为 `em/10`。

```
1.5 弧分 = 1.5 × (1/60)° × (π/180) = 4.3633e-4 rad
300 mm 处可分辨尺寸 = 4.3633e-4 × 300 = 0.13090 mm       （笔画宽度下限）
对应 em  = 0.13090 × 10 = 1.3090 mm
466 px 面板的 mm/px = 36.322 / 466 = 0.077944 mm/px       （推导见 §3.6.1）
⇒ 最小字号 = 1.3090 / 0.077944 = 16.79 px
```

取整并留出余量（中文方块字比拉丁字母更需要尺寸，JPEG 压缩也会糊掉细笔画）：

> **最小可读字号 = 20 px = 4.3% 短边 = `4.3vmin`。** 这是**估计值加工程余量**，不是测量结论；低于 20 px 的字只应当用于非信息性装饰。

#### 3.5.2 推荐字阶

1.3 倍模数（`4.3 × 1.3^n`），全部以 `vmin` 表达：

| 角色 | CSS | 466 px 下 | 设计单位 | 用途 |
| :--- | :--- | ---: | ---: | :--- |
| Micro | `4.3vmin` | 20.0 px | 8.2 du | 最小可读；状态栏、单位、次要标签 |
| Caption | `5.6vmin` | 26.1 px | 10.7 du | 副标题、列表第二行 |
| Body | `7.3vmin` | 34.0 px | 14.0 du | 正文、列表主行 |
| Headline | `9.5vmin` | 44.3 px | 19.5 du | 曲名、页面标题 |
| Display | `12.3vmin` | 57.3 px | 25.4 du | 时间、大数字、单一强调值 |

- `line-height` **应当（SHOULD）** 显式设为 `1.1`–`1.3`。默认值（约 1.2–1.4）在圆屏上会吃掉宝贵的垂直空间（§6.1 算例的两行文本，行高 1.2 → 1.1 节省约 `(7.3 + 4.3) × 0.1 = 1.16vmin ≈ 5 px`）。
- 单行截断用 `white-space: nowrap; overflow: hidden; text-overflow: ellipsis;`。**禁止**用多行溢出隐藏来"藏"内容——被裁掉的半行文字在圆屏上看起来像渲染错误。
- 中文 **应当（SHOULD）** 加 `letter-spacing: .01em`，并用 `line-break: strict` 避免标点悬挂行首。
- 数字用 `font-variant-numeric: tabular-nums`，否则秒数变化时整行宽度抖动。

#### 3.5.3 圆屏行宽上限与算例

```
w_max(y) = ( √(1 − 4·(y/S)²) − 0.08 ) × 100%     （y = 该行中心距圆心的垂直距离）
保守规则：正文文本块位于 |y| ≤ 0.35·S 内时，宽度 ≤ 63% S
```

**算例。** `S = 466 px`，一行正文位于 screen y = 78%（圆心下方 `28% S = 130.5 px`）：

```
t = y/S = 0.28
4t² = 4 × 0.0784 = 0.3136
√(1 − 0.3136) = √0.6864 = 0.82849 → 82.85%
减去左右各 4% 余量：82.85% − 8% = 74.85% → 0.7485 × 466 = 348.8 px
正文字号 5.6vmin = 26.1 px，中文方块字宽度 ≈ 1 em = 26.1 px
⇒ 348.8 / 26.1 = 13.4 ⇒ 本行最多 13 个汉字
```

改用保守规则（正文一律收在中央 70% 带内，取 `w_max = 63.4%`）：`0.634 × 466 = 295.4 px → 295.4 / 26.1 = 11.3`，**最多 11 个汉字**。同理 `9.5vmin`（44.3 px）的大标题在 63.4% 宽度下最多 `295.4 / 44.3 = 6.7`，即 **6 个汉字**；安全区满宽（72% = 335.5 px）下最多 **7 个汉字**。这就是"圆屏标题要短"的定量原因。

### 3.6 触控目标尺寸

#### 3.6.1 mm → px 换算（含推导与假设）

```
1.43 in = 1.43 × 25.4 = 36.322 mm
mm/px = 36.322 / 466 = 0.0779437… mm/px      （反向：12.830 px/mm）
```

**假设**：这里把标称 1.43" 当作**发光区直径**。若厂商的 1.43" 指含边框的外形尺寸，则实际发光区更小、`mm/px` 更小、同样毫米数需要更多像素；因此下表同时给出更保守的 `0.07 mm/px` 一列（相当于发光区直径 ≈ 32.6 mm）。**两列都是估计，不是测量值。**

| 物理尺寸 | `0.0779 mm/px` | 占短边 | `vmin` | 设计单位 | 保守 `0.07 mm/px` |
| :--- | ---: | ---: | ---: | ---: | ---: |
| 5 mm | 64.2 px | 13.8% | `13.8vmin` | 26.5 du | 71.4 px（15.3%） |
| **7 mm（最小）** | **89.8 px** | **19.3%** | **`19.3vmin`** | **37.0 du** | 100.0 px（21.5%） |
| **9 mm（主要操作）** | **115.5 px** | **24.8%** | **`24.8vmin`** | **47.6 du** | 128.6 px（27.6%） |
| 12 mm | 154.0 px | 33.0% | `33.0vmin` | 63.4 du | 171.4 px（36.8%） |

#### 3.6.2 规范性尺寸

| 类别 | 要求 |
| :--- | :--- |
| 主要操作（播放/暂停、接听、确认、滑动解锁把手） | **应当（SHOULD）** ≥ **9 mm** ≈ `24.8vmin`（115.5 px） |
| 一般可点击目标 | **必须（MUST）** ≥ **7 mm** ≈ `19.3vmin`（89.8 px） |
| 密集场景的次要目标（列表行的"更多"、清除） | **可以（MAY）** 降到 **5 mm** ≈ `13.8vmin`（64.2 px）下限 |
| 相邻目标之间的间隙 | **必须（MUST）** ≥ **2 mm** ≈ `5.5vmin`（25.7 px） |
| 命中扩展（hit slop） | 手机 **禁止（MUST NOT）** 在场景之外额外扩大命中区域（§4） |

#### 3.6.3 靠近圆缘的目标

一行 N 个目标能放下的前提是该行**最不利 `|y|`** 处的弦宽足够：`N × 目标尺寸 + (N−1) × 间隙 ≤ w_max(|y|max)`。以 `N = 3`、目标 7 mm、间隙 2 mm（§3.6.2 的 MUST 下限）为例：

```
需要 = 3 × 7 mm + 2 × 2 mm = 25 mm = 25 / 36.322 = 68.83% S
要求 w_max ≥ 68.83%（w_max 本身已扣除左右各 4% 余量）
⇒ √(1 − 4t²) − 0.08 ≥ 0.6883 ⇒ √(1 − 4t²) ≥ 0.7683
⇒ 1 − 4t² ≥ 0.5903 ⇒ t² ≤ 0.10243 ⇒ t ≤ 0.3200
⇒ 该行 |y| 必须 ≤ 0.320·S，即 screen y ∈ [18.0%, 82.0%]
```

推导出的三个数一致：`w_max(0.320) = 76.84% − 8% = 68.8%`，恰好等于所需的 `68.83%`。

若把间隙放宽到 1.5 mm（**低于** §3.6.2 的 2 mm 下限），所需宽度降到 `(21+3)/36.322 = 66.07%`，临界值放宽到 `t ≤ 0.336`。因此：**`|y| ≤ 0.336·S` 只在违反最小间隙要求时才成立的宽松上限；符合规范时应当用 `|y| ≤ 0.320·S`；`|y| ≤ 0.30·S` 是应当遵守的舒适值。**

**危害警示**：`|y| > 0.40·S` 处弦宽只有 52% S（242 px），一个 7 mm 按钮在视觉上会紧贴弧线；`|y| > 0.45·S`（弦宽 35.6% S = 166 px）连两个 7 mm 目标都放不下。**圆屏上下缘不要放交互元素。**

### 3.7 动画与过渡

Profile A 在参考硬件上的捕获/发送节拍约为 **10 fps**，即 **100 ms 一帧**（见 [00-overview.md](00-overview.md) §2 与 [04-video.md](04-video.md)）。

| 要求 | 说明 |
| :--- | :--- |
| **禁止**周期 < 100 ms 的动画 | 10 fps 下不可见；只会让相邻两帧相位不可预测，看起来更抖 |
| 只动 `transform` 与 `opacity` | 二者不触发 layout，也不重新栅格化文字；改 `width/height/top` 会每帧重排 |
| 周期 **应当（SHOULD）** 是 100 ms 的整数倍 | 例：6 秒一圈的进度弧 = `animation: sweep 6s steps(60, end) infinite`，每步 100 ms，正好一帧一步 |
| 状态过渡 **应当（SHOULD）** 取 200–400 ms | 少于 200 ms 只被捕获 1 帧，用户看到"跳变"而非"过渡" |
| **应当（SHOULD）** 由手机逐帧驱动相位 | 手机按捕获时间戳设置 `--wp-t`，场景用 `animation-play-state: paused` + `animation-delay: calc(-1 * var(--wp-t))` 取相位，保证每帧相位与其时间戳严格对应，不会因 JS 抖动错位一帧 |
| 闪烁类动画半周期 **应当（SHOULD）** ≥ 500 ms | 500 ms 半周期 = 5 帧一态，安全；200 ms 半周期 = 2 帧一态，边界情况会丢一半 |

```css
@property --wp-t { syntax: '<time>'; inherits: true; initial-value: 0s; }
.sweep{
  animation: sweep 6s steps(60, end) infinite;
  animation-play-state: paused;               /* 相位完全由手机决定 */
  animation-delay: calc(-1 * var(--wp-t));    /* 手机每帧 setProperty('--wp-t', '1234ms') */
}
@keyframes sweep{ from{ transform: rotate(0turn); } to{ transform: rotate(1turn); } }
```

### 3.8 禁止清单

| 禁止（MUST NOT） | 原因 |
| :--- | :--- |
| 网络字体、`@font-face` 指向 URL、任何外部资源 | 请求会阻塞首个字形或首帧栅格化，可能卡住整整一帧（100 ms @10 fps）；`blockNetworkLoads = true` 下还会直接失败。字体 **必须** 用系统字体或 base64 内嵌（体积上限见 §9） |
| `<video>`、大尺寸 `<canvas>` 动画、WebGL | 解码/合成开销不可控，帧时间抖动直接导致丢帧 |
| 重型 JS 框架（React/Vue 运行时） | 首帧预算 < 500 ms；手表 UI 的复杂度用纯 CSS + 少量 DOM 操作足够 |
| `position: fixed` + `backdrop-filter` / `filter: blur()` | 每帧触发全屏重采样，GPU 开销在 render-to-texture 路径上直接吃掉帧预算 |
| 绝对 `px` 的边距 / 尺寸 / 位置 | 违反 §1；分辨率或渲染分辨率一变就必须重做场景 |
| `overflow: auto/scroll`、任何可滚动区域 | 滚动条改变 ICB 宽度 → `vmin` 漂移；且捕获端不会滚动 |
| 透明或未定义的背景色 | JPEG 无 alpha，透明会变黑（§7） |
| `:hover` / `hover` 媒体查询 / 文本选择 / `<a href>` 导航 | 手表没有悬停；导航会离开场景，捕获端拍到空白页 |
| `transform: scale()` 做整体自适应 | 非整数像素采样使文字发虚；应当用 `vmin` |
| 周期 < 100 ms 的动画 | 采样意义上的混叠（§3.7） |

## 4. 触控坐标的逆映射

触控与渲染是**同一个仿射变换的正反两向**。`TOUCH` 帧以**物理显示像素**为单位携带绝对坐标（14 字节负载的字段布局见 [05-input.md](05-input.md)，本文不重新定义）：

```
scale  = 捕获纹理的物理像素宽 / WebView 的 CSS 像素宽     # §3.2 钉死后 scale == 1.0
S_css  = WebView 的 CSS 像素宽（== 场景的 100vmin）
css_x  = watch_x / scale ;  css_y = watch_y / scale
dx = css_x − S_css/2 ;      dy = css_y − S_css/2

if shape == ROUND and (dx*dx + dy*dy) > (S_css/2)^2:
    丢弃该事件；计入 out_of_circle_touches 统计          # 圆屏不是矩形
```

| 规则 | 要求 |
| :--- | :--- |
| 变换一致性 | 渲染与触控逆映射 **必须（MUST）** 使用同一个 `scale`。相差 ≥ 1 px 时越靠边缘偏差越大：在 `r = 0.5·S_css` 处 1 px 缩放误差会变成约 1 px 横向偏差，叠加到只有 `19.3vmin` 宽的按钮上就会误触。 |
| 圆屏有效性 | 落在内接圆之外（`dx² + dy² > (S_css/2)²`）的触点 **必须（MUST）** 视为无效/误触并丢弃。参考硬件的触摸面板外接方框的四个角在可视圆之外，硬件仍可能上报坐标，不要把它当成合法的边缘点击。 |
| 边界容忍带 | `0.98·(S_css/2) < r ≤ S_css/2` 是"贴边"带。手机 **可以（MAY）** 把触点按径向投影钳制到 `r = 0.98·(S_css/2)` 后再下发，**应当（SHOULD）** 在统计中区分"钳制"与"正常"。 |
| 舍入 | 映射 **必须（MUST）** 保留浮点，**禁止**在命中测试前取整到整数 CSS 像素。WebView 的命中测试使用浮点 CSS 坐标，取整会在圆缘产生系统性的 0.5 px 偏差；取整只允许出现在日志/统计里。 |
| 事件合成 | 手机把触点合成为 `ACTION_DOWN` → `ACTION_MOVE` → `ACTION_UP` 交给 WebView（原始事件类型见 [05-input.md](05-input.md)）。**禁止**只发 `ACTION_DOWN`，否则 `click` 不触发，场景只能用 `pointerdown` 响应。 |
| 命中扩展 | **禁止**在手机侧再扩大一次命中区域。目标尺寸是**场景**的责任（§3.6）；两侧都放大等于把相邻按钮的命中区叠在一起。 |
| 视觉反馈 | `touch` → 屏幕出现反馈要经过"捕获节拍 + RTT"（Profile A 下最坏约 200 ms）。**应当（SHOULD）** 让 `:active` 用高对比变化（背景从 6% 白到 18% 白），而不是细微位移。 |

## 5. 圆屏 / 方屏适配规则

| 元素 | 圆屏（ROUND） | 方屏（SQUARE） | 依据 |
| :--- | :--- | :--- | :--- |
| 状态栏 / 时间 | 顶部居中，宽度 ≤ 61% S，**禁止**左右贴边 | 可通栏，左右各留 4% S | §2.6：`y = ±0.36S` 处 `w_max` 仅 61.4% |
| 列表 | ≤ 4 项；首/末项宽度 ≤ 60% S | 通栏，≤ 6 项 | 首末项位于 `\|y\| ≈ 0.35S` |
| 弧形进度环 | **首选**：圆心 = 屏幕圆心，外径 ≤ 72vmin，环宽 2.5–3vmin | 退化为水平进度条（宽 ≤ 92% S，高 1.5–2vmin） | 圆弧是圆屏的天然进度表达；方屏用直线更符合阅读方向 |
| 封面 / 主视觉 | 圆形或圆角方形，边长 ≤ 26–36vmin | 可放大到 34–46vmin，允许偏心（如左上对齐） | 圆屏上的方形封面必须自带圆角，否则角会顶到弧线 |
| 居中表盘 / 指针 | 全部内容落在 `r ≤ 0.30·S` 内 | 同左 | 表盘是"一眼读取"的信息，必须落在最稳的区域 |
| 底部按钮行 | 2 个 ≥ 7 mm 目标安全；3 个要求该行 `\|y\| ≤ 0.320·S` | ≤ 4 个 ≥ 7 mm 目标，可通栏 | §3.6.3 的反解 |
| 正文文本块 | 宽度 ≤ 88% 安全区宽（= 63.4% S），位于中央 70% 带 | 宽度 ≤ 100% 安全区宽（= 92% S） | §3.5.3 |
| 装饰性全出血 | **可以**铺满外接方框（会被圆裁切） | 同左 | 只有信息禁止被裁切 |
| 边缘进入的气泡/通知 | 宽度上限 = 进入位置的弦宽，需动态计算 | 通栏 | §2.6 |

### 5.1 常见原型与关键内容的落点

| 原型 | 关键内容 | **必须**落点 |
| :--- | :--- | :--- |
| 音乐播放器 | 播放/暂停、曲名 | 播放键中心 `r ≤ 0.20·S`；曲名在中央 70% 带内 |
| 导航 / 方向 | 转向箭头、剩余距离 | 箭头 `r ≤ 0.25·S`；距离文本在中央 70% 带内 |
| 表盘 | 时、分、秒、日期 | 全部 `r ≤ 0.30·S`，指针长度 ≤ 0.28·S |
| 活动环 / 计步 | 三个环、当前数值 | 环外径 ≤ 0.40·S（环属半装饰）；数值 `r ≤ 0.30·S` |
| 通知列表 | 前 3 条摘要 | 每条一行，宽度按 §2.6 逐行计算 |
| 单键确认 | 一个大按钮 | 直径 ≥ `24.8vmin`（9 mm），居中 |
| 设置列表 | 开关、滑块 | 开关中心 `\|y\| ≤ 0.30·S`；行宽 ≤ 72% S |

## 6. 完整示例场景（音乐播放器）

以下场景是完整、可直接运行的 HTML 文档，同时适配圆屏与方屏。存放于 [`../../examples/watch-ui/`](../../examples/watch-ui/)。

```html
<!doctype html>
<html lang="zh-CN" data-screen-shape="round">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<title>WatchPlay 示例场景 · 音乐播放器</title>
<style>
:root{
  /*WP_INJECT*/                /* 手机端在此处替换为 --wp-safe-inset:14vmin; */
  --wp-safe-inset:14vmin;      /* 兜底默认值 = CAPS.safe_inset_permille / 10 + "vmin" */
  --wp-bg:#05070c; --wp-fg:#eef3ff; --wp-dim:#8a93a8; --wp-accent:#4ac6ff;
  --wp-line:rgba(255,255,255,.10); --wp-progress:.38;
  --fs-caption:4.3vmin; --fs-body:5.6vmin; --fs-title:7.3vmin; --fs-headline:9.5vmin;
  --hit:19.3vmin;              /* 7.0 mm 最小可点击目标 */
  --ring:2.6vmin;              /* 环宽 ≈ 1.0 mm，也等于封面相对表盘的 inset */
}
html[data-screen-shape="square"]{ --wp-safe-inset:4vmin; }

*{ box-sizing:border-box; margin:0; padding:0; }
html, body{
  width:100%; height:100%; overflow:hidden; overscroll-behavior:none; touch-action:none;
  background:var(--wp-bg); color:var(--wp-fg);   /* 必须显式：透明背景会编码成黑色（§7） */
  font-family:system-ui,"Roboto","Noto Sans CJK SC","Source Han Sans SC",sans-serif;
  font-variant-numeric:tabular-nums;
  -webkit-user-select:none; user-select:none; -webkit-touch-callout:none;
  -webkit-tap-highlight-color:transparent;
  -webkit-text-size-adjust:100%; text-size-adjust:100%;
}
::-webkit-scrollbar{ width:0; height:0; display:none; }

/* 安全区：只用 vmin 内边距，绝不写 padding:14%（§1.4） */
.safe-area{ height:100%; padding:var(--wp-safe-inset);
            display:flex; flex-direction:column; align-items:center; }
/* 文本：圆屏收在中央 70% 带内（88% × 72% = 63.4% S） */
.text{ display:block; text-align:center; overflow:hidden;
       white-space:nowrap; text-overflow:ellipsis; }
html[data-screen-shape="round"]  .text{ max-width:88%; }
html[data-screen-shape="square"] .text{ max-width:100%; }
.row{ width:100%; display:flex; align-items:center; }

/* 状态行 */
.status{ flex:0 0 5vmin; justify-content:center; }
.status .text{ font-size:var(--fs-caption); color:var(--wp-dim); }

/* 封面 + 进度环（圆屏）：环宽 = 半径的 20% → mask 在 80% 处硬切 */
.dial{ flex:0 0 auto; position:relative; width:26vmin; height:26vmin; margin-top:2vmin; }
html[data-screen-shape="square"] .dial{ width:34vmin; height:34vmin; }
.ring{ position:absolute; inset:0; border-radius:50%;
  background:conic-gradient(var(--wp-accent) calc(var(--wp-progress) * 1turn), var(--wp-line) 0);
  -webkit-mask:radial-gradient(circle,#0000 0 79.9%,#000 80%);
          mask:radial-gradient(circle,#0000 0 79.9%,#000 80%); }
.cover{ position:absolute; inset:var(--ring); border-radius:50%;
        background:linear-gradient(145deg,#1d2740,#0a0d16 70%); }
.play{ position:absolute; inset:var(--ring); border:0; border-radius:50%; background:transparent;
       color:var(--wp-fg); font-size:var(--fs-headline);
       display:flex; align-items:center; justify-content:center; }
.play:active{ background:rgba(255,255,255,.18); }

/* 水平进度条（方屏）：与圆环共享同一份 --wp-progress */
.bar{ flex:0 0 auto; width:100%; height:1.8vmin; margin-top:2vmin;
      border-radius:.9vmin; background:var(--wp-line); overflow:hidden; }
.bar > i{ display:block; height:100%; width:calc(var(--wp-progress) * 100%); background:var(--wp-accent); }
html[data-screen-shape="round"]  .bar{ display:none; }
html[data-screen-shape="square"] .ring{ display:none; }

/* 文案：显式行高 1.1，节省圆屏垂直空间（§3.5.2） */
.meta{ flex:0 0 auto; width:100%; margin-top:1vmin; line-height:1.1;
       display:flex; flex-direction:column; align-items:center; gap:.5vmin; }
.title{ font-size:var(--fs-title); font-weight:600; letter-spacing:.01em; }
.artist{ font-size:var(--fs-caption); color:var(--wp-dim); }

/* 播控：2 个 ≥7 mm 目标；3 个会顶到圆缘（§6.1） */
.nav{ flex:0 0 auto; margin-top:2vmin; justify-content:center; gap:5.5vmin; }
.btn{ width:var(--hit); height:var(--hit); border:0; border-radius:50%;
      background:rgba(255,255,255,.06); color:var(--wp-fg); font-size:var(--fs-body);
      display:flex; align-items:center; justify-content:center; }
.btn:active{ background:rgba(255,255,255,.18); }

/* 形状专属内容：用 display 切换，不重建 DOM（§3.4） */
html[data-screen-shape="round"]  .square-only{ display:none; }
html[data-screen-shape="square"] .square-only{ font-size:var(--fs-caption);
       color:var(--wp-dim); margin-top:2vmin; }
</style>
</head>
<body>
  <main class="safe-area">
    <header class="row status"><span class="text" id="clock">09:41</span></header>
    <section class="dial" aria-label="专辑封面">
      <div class="ring" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="38"></div>
      <div class="cover"></div>
      <button class="play" id="play" aria-label="播放或暂停">II</button>
    </section>
    <div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="38"><i></i></div>
    <section class="meta">
      <h1 class="text title">Night Drive</h1>
      <p class="text artist">WatchPlay Demo</p>
    </section>
    <nav class="row nav">
      <button class="btn" id="prev" aria-label="上一首">&lt;&lt;</button>
      <button class="btn" id="next" aria-label="下一首">&gt;&gt;</button>
    </nav>
    <p class="text square-only">方屏横向更宽：正文可用 92% S</p>
  </main>
<script>
  // 场景只暴露无副作用的状态设置接口；进度与时间由手机驱动，保证帧确定性（§3.7）
  window.watchplay = {
    setProgress: function (p) { document.documentElement.style.setProperty('--wp-progress', String(p)); },
    setPlaying:  function (on) { document.getElementById('play').textContent = on ? 'II' : '>'; },
    setClock:    function (t) { document.getElementById('clock').textContent = t; }
  };
</script>
</body>
</html>
```

> **字形警告**：示例用 `II` / `<<` / `>>` 代替 `▶ ⏮ ⏭ ❚❚`，因为这类码位依赖系统是否带符号字体，缺字会渲染成方框（豆腐块）。生产场景 **应当（SHOULD）** 用纯 CSS（伪元素 + `border`/`clip-path`）或内联 SVG 绘制图标。

### 6.1 该场景在圆屏（默认 140‰，S = 466 px）下的布局算例

安全区内容盒 = `100% − 2×14% = 72% S = 72vmin = 335.5 px`，屏幕 y 从 14% 到 86%。圆心在 screen y = 50%，故"相对圆心 `y`" = `screen y − 50%`。

| 元素 | 高度 | 累计 local top | screen y | 相对圆心 `y` | 该处 `w_max` | 校验 |
| :--- | ---: | ---: | ---: | ---: | ---: | :--- |
| `.status` | 5.0vmin | 0 → 5.0 | 14% → 19% | −36% … −31% | 61.4% | 状态行极窄，用 61% 上限 |
| `.dial`（含 2vmin 上边距） | 26.0vmin | 7.0 → 33.0 | 21% → 47% | −29% … −3% | — | 角点半径 `√(29²+13²) = 31.8vmin ≤ 50vmin` ✓ |
| `.meta`（含 1vmin 上边距） | 7.3×1.1 + 0.5 + 4.3×1.1 ≈ 13.3vmin | 34.0 → 47.3 | 48% → 61.3% | −2% … +11.3% | 89.4% | 安全区宽 72% < 89.4% ✓ |
| `.nav`（含 2vmin 上边距） | 19.3vmin | 49.3 → 68.6 | 63.3% → 82.6% | +13.3% … +32.6% | 67.8% | 需要 `2×19.3 + 5.5 = 44.1vmin` ≤ 67.8% ✓ 余 23.7% |
| （底部余量） | 3.4vmin | 68.6 → 72.0 | 82.6% → 86% | | | |

**最重要的结论**：`.nav` 最不利位置是 `y = +32.6%`，弦宽上限只有 67.8% S。若把播控行推到安全区下沿（`y = +36%`，上限 61.4% S），就放不下 3 个 7 mm 目标——那需要 68.8% S 且 `\|y\| ≤ 0.320·S`（§3.6.3）。**圆屏上最后一行不应当被 `justify-content: space-between` 推到安全区下沿**：它必须至少提前 4vmin（约 19 px）结束。本示例因此改用固定 `margin-top`，并把 3 个按钮减为 2 个。

**方屏对照**：安全区 = `92vmin = 428.7 px`；`.dial` 放大到 34vmin（158.4 px）；`.ring` 由 `.bar` 取代；`.text` 可取满 100% 安全区宽（92% S = 428.7 px）。垂直总占用 `5（状态）+ 2（表盘上边距）+ 34（表盘）+ 2（进度条上边距）+ 1.8（进度条）+ 1（文案上边距）+ 13.3（文案）+ 2（播控上边距）+ 19.3（播控）= 80.4vmin ≤ 92vmin` ✓。

## 7. 捕获路径说明（给渲染实现者）

只列出**会影响场景写法**的约束；编码、分块、流控见 [04-video.md](04-video.md) 与 [01-transport.md](01-transport.md)。

1. **主路径**：WebView 渲染结果经 **OpenGL render-to-texture** 捕获到一张与手表物理分辨率等大的纹理。**`PixelCopy` 是文档化的降帧率兜底路径**（依赖窗口合成器，通常只能到个位数 fps）。手机 **必须（MUST）** 让捕获纹理尺寸等于 `CAPS_ACK` 协商的分辨率，**禁止**加黑边或信箱式缩放。
2. **静态、自包含、确定性重绘**：捕获可能发生在任意时刻，场景 **必须（MUST）** 不依赖"某个异步资源刚好加载完"。手机 **应当（SHOULD）** 在首个捕获帧前等待 `onPageCommitVisible` 或等价的"首次绘制完成"信号。
3. **透明背景 → 黑色**：JPEG 没有 alpha 通道，未显式设置 `background` 的透明区域编码后是**纯黑**。场景 **必须（MUST）** 显式设置不透明背景；手机 **应当（SHOULD）** 在提交首帧前断言 `getComputedStyle(document.body).backgroundColor` 的 alpha 为 1。
4. **不依赖滚动**：捕获端不会滚动页面。场景 **禁止（MUST NOT）** 依赖滚动条或 `overflow: auto`——它既会改变 ICB 宽度（使 `vmin` 漂移，§3.1），也会让被裁切的内容永远拍不到。
5. **帧节拍**：Profile A 参考实现约 10 fps；小于 100 ms 的视觉变化可能只被采到一帧或完全采不到（§3.7）。
6. **合成层**：`will-change`、`transform: translateZ(0)`、固定定位会创建额外合成层，在 RTT 路径上可能引入一次额外的离屏合成。**应当（SHOULD）** 只在确实需要动画的元素上开 `will-change`，动画结束后移除。

## 8. 一致性检查表

**场景（Scene）**

- [ ] 全文档**没有**绝对 `px` 的边距、尺寸或定位；所有尺寸基于 `%` / `vmin` / `em`。
- [ ] 内边距来自 `var(--wp-safe-inset)`；手动改成 `0vmin` 后内容会立刻贴边（证明它真的生效，而非被硬编码覆盖）。
- [ ] `data-screen-shape="round"` 与 `"square"` 下均无文本或图标被裁切、无元素越过安全区。
- [ ] `scrollHeight <= clientHeight` 且 `scrollWidth <= clientWidth`（无滚动）。
- [ ] 加载期间**零网络请求**（`shouldInterceptRequest` 计数为 0）。
- [ ] 显式设置了不透明 `background-color`。
- [ ] 所有可点击元素 ≥ 7 mm（≥ `19.3vmin`）；相邻目标间隙 ≥ 2 mm（≥ `5.5vmin`）；**不存在** `\|y\| > 0.40·S` 的交互元素。
- [ ] 所有信息性文字 ≥ 20 px 等效（≥ `4.3vmin`）。
- [ ] 正文文本块宽度 ≤ 88% 安全区宽（圆屏），且位于 `\|y\| ≤ 0.35·S`。
- [ ] 无周期 < 100 ms 的动画；动画只动 `transform` / `opacity`；无网络字体、无 `<video>`、无 JS 框架运行时、无 `backdrop-filter`。

**渲染器 / 手机端**

- [ ] WebView 以 `densityDpi = 160` 的上下文创建，并按 `CAPS.width × CAPS.height` 精确 `measure`/`layout`；`scale == 1.0`（或已按 §4 记录实际 `scale`）。
- [ ] `textZoom = 100`，`minimumFontSize` 与 `minimumLogicalFontSize` 已显式设为最小值。
- [ ] `--wp-safe-inset` 与 `data-screen-shape` 在**首次绘制之前**注入（document-start 或字符串替换）。
- [ ] 注入值**等于** `safe_inset_permille / 10 + "vmin"`，未被任何本地默认值覆盖。
- [ ] 捕获纹理尺寸 == 协商分辨率，无缩放、无黑边；首帧背景非透明。
- [ ] 触控逆映射使用与渲染相同的 `scale`；圆屏上丢弃 `r > S_css/2` 的触点并计数；映射全程使用浮点。
- [ ] 触点被合成为完整的 DOWN → MOVE → UP 序列；捕获节拍与 `--wp-t` 使用同一时间戳源。

## 9. 开放问题 / Open Questions（v0.2）

1. **场景清单（scene manifest）格式**：是否标准化一个 `scene.json`（名称、图标、所需能力、允许的形状、首选 Profile）？目前场景完全依赖手机端的私有约定。
2. **强制最小字号**：是否把 20 px（`4.3vmin`）写入协议作为 **必须（MUST）**，还是继续留作指导值？强制有利于可读性，但会限制高信息密度场景。
3. **第三种屏幕形状**：`ROUND` / `SQUARE` 之外如何处理**矩形但带圆角**的屏（例如 410×502、圆角半径 40 px）？新增 shape 枚举，还是给 `CAPS` 增加一个圆角半径字段？
4. **逐边安全区**：是否需要把 `safe_inset_permille` 拆成上/右/下/左四个值（用于刘海、下巴、非对称面板边框）？
5. **标准化的弧形/环形组件**：是否定义一组协议级 CSS 类或自定义属性（例如 `--wp-arc-value`），让进度环在所有场景里表现一致？
6. **手机端应当注入哪些只读变量**：`--wp-screen-w` / `--wp-screen-h` / `--wp-fps` / `--wp-profile`？目前场景无法感知自己跑在 10 fps 还是 30 fps 的链路上。
7. **暗/亮主题**：是否用 `prefers-color-scheme` 协商，还是由场景自己管理？手表端没有系统主题概念，但手机端可以按时间切换。
8. **手势**：滑动阈值与坐标系归属（手机判定 vs WebView 判定）是否进入协议？v0.1 只定义了离散触控事件（[05-input.md](05-input.md)）。
9. **内嵌字体的体积上限**：允许 base64 内嵌字体的最大字节数，以及它是否计入首帧预算。
10. **关键内容圆**：是否把"关键内容必须落在 `r ≤ 0.30·S` 内"从建议升级为 **必须（MUST）**，并由此推导出自动检查工具。
11. **渲染分辨率降采样**：低带宽模式下允许手机以 0.5× 渲染再上采样吗？若允许，需要在 `CAPS_ACK` 中明确协商，并规定文字的抗混叠要求。

## 10. 变更控制

本文定义的**设计坐标系、安全区语义与 `--wp-safe-inset` 注入契约**在协议 **1.0 之前不得变更**；0.x 期间若必须变更，须同时提供兼容读路径（场景应当忽略未知的自定义属性）。相关文档：[00-overview.md](00-overview.md)、[02-wire-format.md](02-wire-format.md)、[03-session.md](03-session.md)、[04-video.md](04-video.md)、[05-input.md](05-input.md)、[07-capabilities.md](07-capabilities.md)、[99-changelog.md](99-changelog.md)。
