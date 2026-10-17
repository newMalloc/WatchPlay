# WatchPlay 介绍动画

一支 53 秒的产品介绍动画。**画面是一张网页**（`index.html`）：1920×1080 的舞台，8 段时间轴，每个时刻的画面由 JS 算出来；用本机 Edge 逐帧截图，再用 ffmpeg 编码成 mp4。背景音也是代码合成的（`music.mjs`），没有引用任何现成素材。

片子里出现的每个数字都能在仓库里找到出处，见下面的时间轴表。

## 成品

| 文件 / 位置 | 说明 |
| :--- | :--- |
| [issue #1](https://github.com/newMalloc/WatchPlay/issues/1) 里的评论附件 | README 首屏的播放器指向它，`user-attachments` 链接，5.0 MB |
| `WatchPlay-intro-1080p.mp4` | 同一支片子的仓库副本：1920×1080 @ 30 fps，4.78 MB，带背景音 |
| `preview.gif` | 960 px 宽、7.4 秒的无声预览，聊天工具里转发用 |
| `poster.png` | 封面帧（15.6 s），分享用 |

> **不要删 issue #1，也不要删那条评论**：README 里的播放器就是它的 `user-attachments` 链接，删了首屏就变成死链。
>
> README 里也不要写 `<video>` 标签：GitHub 的 Markdown 会把它剥掉。能内联播放的只有 issue / 评论附件，换附件用
> `gh issue comment 1 --repo newMalloc/WatchPlay --attach <文件> --body "…"`，然后把 README 里那行 URL 换掉。

## 文件

| 文件 | 作用 |
| :--- | :--- |
| `index.html` | 动画本体：舞台、时间轴、8 段渲染函数、`window.seek(ms)` |
| `serve.mjs` | 本地静态服务器（默认 `http://127.0.0.1:5311`） |
| `capture.mjs` | 逐帧截图到 `frames/f%05d.jpg` |
| `snap.mjs` | 抽查某几个时刻，出 PNG 供人眼看 |
| `probe.mjs` | 打印页面报错、`__ready`、总时长 |
| `music.mjs` | 现场合成 53.4 秒 48 kHz 立体声背景音 |
| `build.ps1` | 合成音轨 + 编码 1080p / 720p + 抽封面 + 切 GIF |
| `logo.png` | 从启动图标同一处裁出来（由 `android/art/make_launcher_icons.py` 生成，别手改） |

## 时间轴

| 秒 | 内容 | 数字的出处 |
| :--- | :--- | :--- |
| 0–4.7 | 开场：图标、名字、一句话定位 | — |
| 4.7–11.9 | 现状对比：每个型号重写原生 App / MediaProjection 投整屏 → 渲染即投屏 | [06-rendering.md](../spec/06-rendering.md) §2.3 |
| 11.9–21.4 | 手机 WebView 渲染、手表只显示；帧流 | `docs/guides/demo-frame-preview.png`（真机 loopback：466×466、7.0 fps、12 ms、11 kB） |
| 21.4–28.6 | 16 字节帧头、20 字节视频子头、8 123 B → 37 块、Credit 64 | [02-wire-format.md](../spec/02-wire-format.md) §1、[04-video.md](../spec/04-video.md) §3.4 |
| 28.6–35.2 | 码率自适应：quality 85→40，24.2 / 17.1 / 14.0 / 11.9 kB | [04-video.md](../spec/04-video.md) §6；四个字节数是实测值 |
| 35.2–42.2 | 触控回传：点「音乐」→ TOUCH 14 B → 手机切页 → 手表跟着变 | [05-input.md](../spec/05-input.md) §1、§2.1 |
| 42.2–48.4 | 仓库：规范 00–07、工具链 49 项、Android 163 + 18、四承载 | `AGENTS.md`、`README.md` |
| 48.4–53.0 | 收尾：快速开始命令、仓库地址 | — |

## 为什么手表画面是 iframe 而不是截图

舞台上的那块手表，直接嵌了 `examples/watch-ui/demo-scene.html`（真文件，不是截屏），逐帧调 `watchplay.go('music')` 和 `watchplay.render()` 把它驱动到该有的状态。好处有两个：文字在任何尺寸下都是矢量的；顺带证明这份 HTML 确实能在手表尺寸里跑。

代价是页面必须走 http —— 截图时父页要访问子帧的 `contentWindow`，`file://` 下会被同源策略拦掉，所以双击 `index.html` 只能看到静态首帧。另外 `capture.mjs` 会给场景注入一条 `transition:none`：场景自带的 0.12 s 过渡会让「第 N 帧长什么样」取决于真实时间，逐帧截图就不可复现了。

## 重新出片

需要 Node（`playwright-core` 驱动本机 Edge，`@ffmpeg-installer/ffmpeg` 提供 ffmpeg）：

```pwsh
cd docs/video
pnpm install
node serve.mjs                     # 浏览器里看：http://127.0.0.1:5311/docs/video/index.html
node snap.mjs 2.2 15.0 24.5        # 抽查这几秒，PNG 出到 snap/
node capture.mjs 0 53 30 frames 95 # 逐帧 1920x1080@30fps，约 3–5 分钟
pwsh -File build.ps1 -Deliver      # 编码 mp4 + 封面 + GIF，并把成品复制到本目录
```

`index.html` 支持两个参数：`?t=23.5` 停在某一秒，`?static=1` 只渲染第 0 帧（截图脚本用这个）。

## 背景音

`music.mjs` 合成 53.4 秒：120 BPM、一小节 2 秒、27 小节；Am7–Fmaj7–Cmaj7–G6 四小节一循环；铺底 pad + 低音脉冲 + 八分音符铃声 + 弱底鼓 + 气声踩镲，并在八段的起点叠一层噪声渐强做转场（`CUES`，与 `index.html` 的 `SCENES` 对齐）。想改风格就改 `KEYS` / `BARS` / `MASTER`。单独试听：

```pwsh
node music.mjs out\theme.wav
```

## 改了动画之后

`snap.mjs` 先看图，再出片。`frames/`、`snap/`、`out/` 都在 `.gitignore` 里，进仓库的只有源码和上面那三个成品。
