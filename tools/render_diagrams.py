#!/usr/bin/env python3
"""把 docs/diagrams/*.mmd 渲染成同名 .svg。

这是 tools/ 里唯一需要联网的脚本。文档里的架构图、状态机图都是 Mermaid 源码
（`docs/diagrams/*.mmd`），但 GitHub 的 Mermaid 渲染器不支持 subgraph 内的
`direction`，会把并排的两栏摊成一条几千像素宽的横带，缩放到正文栏宽度后文字
不可读。所以图以渲染好的 SVG 形式提交，文档引用 SVG。

改了 .mmd 之后必须重新跑本脚本，否则提交的 SVG 会和源码不一致。

用法：

    python tools/render_diagrams.py            # 重新渲染全部 .mmd
    python tools/render_diagrams.py --list     # 只列出待渲染的图
"""

from __future__ import annotations

import argparse
import base64
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DIAGRAM_DIR = Path(__file__).resolve().parent.parent / "docs" / "diagrams"
ENDPOINT = "https://mermaid.ink/svg/"
USER_AGENT = "watchplay-render-diagrams/1.0"
TIMEOUT_S = 60.0
ATTEMPTS = 4

# 渲染服务的默认字体栈以 "trebuchet ms" 打头。服务端（Linux）没有该字体，实际用
# Liberation Sans 度量标签宽度并写进 <foreignObject width="…">；而 Windows / macOS
# 客户端有 Trebuchet MS，字比度量值宽约 18%，foreignObject 会把文字裁掉
# （"notify / write" 变成 "notify / writ"）。换成等度量的 Arial / Helvetica：
# 各平台要么有 Arial，要么有与之度量兼容的 Liberation Sans，量出来的宽度一致。
DEFAULT_FONT_STACK = '"trebuchet ms",verdana,arial,sans-serif'
PORTABLE_FONT_STACK = "Arial,Helvetica,sans-serif"


def sources() -> list[Path]:
    return sorted(DIAGRAM_DIR.glob("*.mmd"))


def render(source: str) -> str:
    """提交 Mermaid 源码，返回渲染好的 SVG 文本。"""
    encoded = base64.urlsafe_b64encode(source.encode("utf-8")).decode("ascii")
    request = urllib.request.Request(
        ENDPOINT + encoded, headers={"User-Agent": USER_AGENT}
    )
    last: Exception | None = None
    for attempt in range(1, ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
                return response.read().decode("utf-8")
        except (urllib.error.URLError, TimeoutError) as exc:  # 渲染服务会偶发 503
            last = exc
            if attempt < ATTEMPTS:
                time.sleep(2.0 * attempt)
    raise SystemExit(f"渲染失败（{ATTEMPTS} 次尝试）：{last}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--list", action="store_true", help="只列出 .mmd 源文件，不渲染"
    )
    args = parser.parse_args(argv)

    targets = sources()
    if not targets:
        print(f"没有找到 .mmd 源文件：{DIAGRAM_DIR}", file=sys.stderr)
        return 1

    if args.list:
        for mmd in targets:
            print(mmd.relative_to(DIAGRAM_DIR.parent.parent))
        return 0

    for mmd in targets:
        source = mmd.read_text(encoding="utf-8")
        svg = render(source)
        if "<svg" not in svg:
            print(f"{mmd.name}: 渲染结果不是 SVG，已跳过", file=sys.stderr)
            return 1
        svg = svg.replace(DEFAULT_FONT_STACK, PORTABLE_FONT_STACK)
        out = mmd.with_suffix(".svg")
        out.write_text(svg, encoding="utf-8")
        print(f"{mmd.name} -> {out.name}  ({len(svg)} 字符)")

    print(f"渲染了 {len(targets)} 张图到 {DIAGRAM_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
