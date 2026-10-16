# 示例手表 UI

这里是符合 [协议渲染规范](../../docs/spec/06-rendering.md) 的场景样例。它们同时是**可运行的演示**和**规范的可执行说明**——每一份都刻意展示规范里的某几条约束。

## 怎么用

### 在浏览器里看

直接打开 `.html` 文件即可。这些场景是自包含的单文件，不请求任何外部资源（字体、图片、脚本全部内联），所以在浏览器里和在手表上的渲染结果一致。

### 在手机端 Demo 里看

`android/demo` 会加载这些文件。用法见 [快速开始](../../docs/guides/getting-started.md)。

场景挂在 `examples/watch-ui/`，通过 `WatchScene` 的名字加载。

### 在真实手表上看

手表端不了解 HTML，它只收到编码后的像素。要让手表显示某个场景，需要：

1. 手机端 `UIRenderModule` 加载该 HTML
2. 捕获 → 编码 → 传输（见 [04-video.md](../../docs/spec/04-video.md)）

## 场景清单

| 文件 | 展示的规范要点 |
| :--- | :--- |
| [`music-player.html`](music-player.html) | 安全区变量注入、`conic-gradient` 弧形进度条、圆屏/方屏自适应、触摸目标尺寸、纯状态更新不做动画 |

## 写自己的场景

### 必须遵守的约束

这些不是风格建议，而是**规范要求**（见 [06-rendering.md](../../docs/spec/06-rendering.md)）。

| 约束 | 为什么 |
| :--- | :--- |
| **必须**设置显式背景色 | JPEG 没有 alpha 通道，未定义背景会渲染成黑色 |
| **禁止**使用绝对像素做边距 | 同一份 HTML 要适配不同分辨率的手表 |
| **禁止**滚动 | WebView 捕获的是视口，滚动会让触控坐标映射出错 |
| **禁止**外部资源（字体/图片/脚本） | 一次网络请求落在捕获帧中间 = 一帧可见的卡顿；手表的 WebView 也未必有网 |
| **禁止**重度动画 | Profile A 下捕获约 10 fps，比这快的动画看不见，还会挤占编码预算 |
| **应当**用 `vmin` 做尺寸单位 | 圆屏的安全区是正方形，两个轴必须按短边等量内缩 |

### 起点模板

```html
<!DOCTYPE html>
<html lang="zh-CN" data-screen-shape="round">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
  :root { --wp-safe-inset: 14vmin; --bg: #0b0d12; --fg: #f2f4f8; }
  html[data-screen-shape="square"] { --wp-safe-inset: 4vmin; }

  * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body {
    width: 100%; height: 100%; overflow: hidden;
    background: var(--bg);            /* 必须：JPEG 无 alpha */
    color: var(--fg);
    font-family: system-ui, "Noto Sans CJK SC", sans-serif;
    -webkit-user-select: none; user-select: none; touch-action: none;
  }
  .safe {
    position: absolute; inset: 0;
    /* vmin 而非 %：圆屏安全区是正方形，两轴必须等量内缩 */
    padding: var(--wp-safe-inset);
    display: flex; flex-direction: column;
    align-items: center; justify-content: center; gap: 3vmin;
  }
</style>
</head>
<body>
  <div class="safe">
    <h1 style="font-size:8vmin">Hello Watch</h1>
  </div>
</body>
</html>
```

### 手机怎么把协商结果传进页面

手机端 `UIRenderModule` **必须**在 **document-start** 阶段把 `CAPS_ACK` 协商出的参数注入为 CSS 变量——不能等加载完成后再注入，否则首帧会带错误的安全区（表现为首帧内容贴边，第二帧才跳到位）。场景不应当自己猜测这些值。

```kotlin
// com.watchplay.sdk.render
val insetVmin = caps.safeInsetPermille / 10.0                       // 140 -> 14.0
val shape = if (caps.screenShape == SHAPE_ROUND) "round" else "square"
val js = """
    document.documentElement.dataset.screenShape = '$shape';
    document.documentElement.style.setProperty('--wp-safe-inset', '${insetVmin}vmin');
""".trimIndent()
WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
```

其中：

- `shape` 来自 `DISPLAY.shape`（`1` = 圆形 → `"round"`，其余 → `"square"`）
- `insetVmin` = `DISPLAY.safe_inset_permille / 10.0`（圆屏 140‰ → `14.0vmin`）
- 完整注入代码与兜底路径见 [06-rendering.md §3.2](../../docs/spec/06-rendering.md)

### 用浏览器调试

场景里可以加一点只在开发时用的辅助代码，用来可视化安全区：

```html
<style>
  html[data-debug="1"] .safe { outline: 1px dashed rgba(255,0,0,.5); }
  html[data-debug="1"] body::after {
    content: ""; position: absolute; inset: 0; border-radius: 50%;
    box-shadow: inset 0 0 0 2px rgba(255,255,0,.4); pointer-events: none;
  }
</style>
```

在浏览器控制台里执行 `document.documentElement.dataset.debug = '1'` 打开。**发布前删掉**，或者不删也无妨——手表上没人会设这个属性。

### 圆屏的检查方法

把浏览器窗口调成正方形（例如 466×466），然后：

1. 打开 `data-debug`，确认黄色圆圈代表屏幕边缘。
2. 检查所有文字是否在**内接正方形**之内（红色虚线框）。
3. 检查有没有元素贴在圆圈附近——近边缘的触控目标容易误触，且视觉上会被切掉。

内接正方形的硬极限是每边内缩 `(1 − 1/√2)/2 ≈ 14.64%`；超过这个值就一定有内容落在圆外。圆屏默认上报 `140‰`（14%），留了约 0.6% 的安全裕度。
