# AGENTS.md

WatchPlay is a phone → watch mirroring protocol: the phone renders a watch UI in HTML + CSS, captures it from a WebView, encodes it as MJPEG/H.264 and sends it over BLE, classic Bluetooth or Wi-Fi; touches travel back. This repository holds the specification and the reference implementations.

## The spec wins

`docs/spec/` is authoritative. When code and spec disagree, fix the code. Every byte-level claim in the spec is expected to be checkable against `tools/` or a committed trace.

Code style, commit format and test requirements live in [CONTRIBUTING.md](CONTRIBUTING.md). Only the rules that bind an agent are repeated here.

## Layout

| Path | Contents |
| :--- | :--- |
| `docs/spec/` | The protocol, 00–07 plus 99-changelog |
| `docs/diagrams/` | Figures: Mermaid source `.mmd` + rendered `.svg` |
| `docs/guides/` | Usage guide |
| `tools/` | Executable reference implementation, tests, linters (stdlib only) |
| `examples/` | Sample watch UI (HTML + CSS), byte-level traces |
| `android/sdk`, `android/demo` | Phone-side Kotlin SDK and sample app (renders `examples/watch-ui/demo-scene.html` through `WebViewRenderModule`, loopback demo, BLE scan/connect) |
| `android/sink` | Watch-side app for a second Android device: GATT server, JPEG decode, touch back |
| `watch/docs/` | Watch-side reference architecture for one board. Documentation only, no firmware source |

## Commands

Python 3.10+, no third-party packages:

```pwsh
python tools/test_protocol.py        # 48 tests; also runs the doc link check
python tools/gen_traces.py --check   # committed traces match the generator
python tools/lint_scenes.py          # examples/watch-ui obeys the rendering spec
python tools/check_doc_links.py      # relative links and anchors
```

After editing `docs/diagrams/*.mmd`:

```pwsh
python tools/render_diagrams.py      # the only script that needs the network
```

Android lives in `android/`: `./gradlew :sdk:assembleDebug :sdk:test` (122 JVM tests) and
`./gradlew :demo:test` (12 more, the demo app's data contract), plus `:demo:assembleDebug` and
`:sink:assembleDebug` for the two apps. JDK 17 + SDK 34.

## Changes that must travel together

- Protocol bytes: `watchplay_proto.py`, the assertions in `test_protocol.py`, a regenerated trace via `gen_traces.py`, and an entry in `99-changelog.md`. CI fails on any of these missing.
- A `.mmd` edit without re-rendering leaves the SVG out of sync with its source. CI does not catch this.
- Adding an import to `tools/` requires adding it to `TestToolsAreStdlibOnly.ALLOWED`; that test parses real imports with `ast`, so text matching does not fool it.
- `TestFrozenConstants` freezes released wire values. It is there to stop drive-by constant edits, not to be relaxed.

## Documentation and comments

- Explain why, not what. A comment that restates the next line is deleted, not kept.
- No play-by-play. Do not narrate a diff, a debugging session or a build order ("first I tried X, then Y") in comments, commit bodies or docs. State the decision and the constraint that forced it.
- Commit bodies: one or two sentences on what changed and why, plus whatever a reviewer would otherwise trip over. Not a per-file list.
- Replace stale prose instead of annotating it. A "was X, now Y" note belongs in `99-changelog.md` and nowhere else.
- Tables over paragraphs; numbers over adjectives.
- The reference hardware is an example, never the subject. Do not write "the watch advertises" when you mean BLE, or "ESP32-S3 can't" when you mean a capability the protocol does not require. Name the bearer or the capability; put board-specific facts under "reference implementation", and keep them out of conformance requirements.

## Pitfalls

- Android builds on AGP 9.1's built-in Kotlin support. Do not add the `org.jetbrains.kotlin.android` plugin — it dies on a `BaseExtension` cast. Gradle comes from the wrapper (9.3.1). `android/local.properties` is local, never committed.
- Chunking is `ceil(frame_bytes / (max_chunk_bytes - 20))`, not `ceil(frame_bytes / max_chunk_bytes)`. 8123 bytes at 244 is 37 chunks; 34 is wrong.
- `:demo` stages `examples/watch-ui/demo-scene.html` into `build/generated/demoSceneAssets` before merging assets. `packaging.resources.excludes` does not filter assets, and an absolute asset source path is silently ignored — pointing the source set at `examples/watch-ui` ships the preview shell and its screenshots into the APK.
- Diagram rendering rewrites the font stack to Arial/Helvetica. The render service measures label widths with Liberation Sans, so a client that has Trebuchet MS (~18% wider) would clip text inside `foreignObject`. Do not remove that rewrite.
