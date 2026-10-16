"""Lint WatchPlay watch-UI scenes against the rendering spec.

`examples/watch-ui/` files are described as *conformance examples*, so they had
better conform. The rules an author is most likely to get subtly wrong are also
the ones a machine can check:

  * touch targets below the 7 mm minimum (19.3vmin at 466 px)
  * a missing or mis-named safe-area custom property
  * `%` padding for the safe area, which resolves per-axis and skews on
    non-square panels -- the spec requires `vmin`
  * a transparent background, which renders as black in JPEG because JPEG has
    no alpha channel
  * external resources, which can land mid-capture-frame and stall the stream
  * scrolling / zooming, which breaks the touch-coordinate inverse mapping

This is a lint, not a browser: it parses the CSS textually. It is deliberately
conservative -- it flags what it can prove and stays quiet otherwise, so a false
negative is preferable to nagging about valid scenes.

    python tools/lint_scenes.py                 # lint examples/watch-ui/*.html
    python tools/lint_scenes.py path/to/scene.html
    python tools/lint_scenes.py --quiet         # only print failures
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_SCENES = REPO / "examples" / "watch-ui"

#: Reference panel: 1.43" 466x466 AMOLED. 466 px across ~36.3 mm of glass.
REFERENCE_PANEL_PX = 466.0
REFERENCE_MM_PER_PX = 36.322 / 466.0  # ~0.0779, an estimate (spec 06 section 3.6)

#: Spec minimum touch target: 7 mm. The spec states this as 19.3vmin.
MIN_TARGET_MM = 7.0
MIN_TARGET_VMIN = 19.3

FORBIDDEN_URL_ATTRS = re.compile(
    r"""\b(?:src|href)\s*=\s*["'](?!#|data:|mailto:)([^"']+)["']""",
    re.IGNORECASE,
)
EXTERNAL_SCHEME = re.compile(r"^(?:https?:)?//", re.IGNORECASE)
BLOCKED_TAGS = ("<img", "<video", "<audio", "<iframe", "<object", "<embed", "<link")

VMIN_RE = re.compile(r"(-?\d+(?:\.\d+)?)\s*vmin\b")
PX_RE = re.compile(r"(-?\d+(?:\.\d+)?)\s*px\b")
SAFE_PROP_NAMES = ("--wp-safe-inset", "--safe-inset", "--safe-area-inset")


def vmin_to_mm(vmin: float) -> float:
    px = vmin / 100.0 * REFERENCE_PANEL_PX
    return px * REFERENCE_MM_PER_PX


def declared_safe_property(css: str) -> str | None:
    for name in SAFE_PROP_NAMES:
        if re.search(re.escape(name) + r"\s*:", css):
            return name
    return None


def safe_property_values(css: str, prop: str) -> list[str]:
    """Every value assigned to the safe-area custom property."""
    return [m.group(1).strip() for m in re.finditer(re.escape(prop) + r"\s*:\s*([^;}}]+)", css)]


def unresolved_unit_for(css: str, value: str) -> str | None:
    """Classify a CSS value as 'vmin', 'percent', 'px', or None (unresolved).

    `var(--x)` is resolved against the property's own declaration, because a
    scene that writes `--wp-safe-inset: 14vmin` and then `padding:
    var(--wp-safe-inset)` is correct -- the unit lives in the declaration.
    """
    if "var(" in value:
        names = re.findall(r"var\(\s*(--[\w-]+)", value)
        for name in names:
            for declared in safe_property_values(css, name):
                kind = unresolved_unit_for(css, declared)
                if kind:
                    return kind
        return None
    if "vmin" in value:
        return "vmin"
    if "px" in value:
        return "px"
    if "%" in value:
        return "percent"
    return None


def lint_scene(path: Path) -> tuple[list[str], list[str]]:
    """Return (errors, warnings) for one scene file."""
    errors: list[str] = []
    warnings: list[str] = []
    text = path.read_text(encoding="utf-8", errors="replace")
    lower = text.lower()

    # --- external resources ------------------------------------------------
    for tag in BLOCKED_TAGS:
        if tag in lower and tag != "<link":
            errors.append(f"uses {tag}>; scenes must be fully self-contained (spec 06 section 3.7)")
    for match in FORBIDDEN_URL_ATTRS.finditer(text):
        url = match.group(1).strip()
        if EXTERNAL_SCHEME.match(url) or (
            not url.startswith("#") and "." in url.split("/")[0] and not url.startswith("data:")
        ):
            errors.append(
                f"references external resource {url!r}; a network fetch can land "
                "mid-capture-frame and stall the stream"
            )
    if "@import" in lower:
        errors.append("uses CSS @import; scenes must not fetch external stylesheets")
    if re.search(r"url\(\s*['\"]?(?!data:)[^)]*\)", lower):
        # url() with a non-data target means a file or network fetch.
        for m in re.finditer(r"url\(\s*['\"]?([^)'\"]+)", text, re.IGNORECASE):
            target = m.group(1).strip()
            if not target.startswith("data:"):
                errors.append(f"CSS url({target!r}) is not a data: URI; keep scenes self-contained")

    # --- background ---------------------------------------------------------
    body_rule = re.search(r"\bhtml\s*,\s*body\s*\{([^}]*)\}", text) or re.search(
        r"\bbody\s*\{([^}]*)\}", text
    )
    has_explicit_bg = bool(
        re.search(r"background(?:-color)?\s*:\s*(?!none|transparent)", text, re.IGNORECASE)
    )
    if not has_explicit_bg:
        errors.append(
            "no explicit background colour; JPEG has no alpha so a transparent "
            "body renders as black (spec 06 section 3.7)"
        )
    if body_rule and re.search(r"background\s*:\s*transparent", body_rule.group(1) or "", re.I):
        errors.append("body background is transparent; set an opaque colour")

    # --- safe area ----------------------------------------------------------
    prop = declared_safe_property(text)
    if prop is None:
        errors.append(
            "does not declare a safe-area custom property "
            f"(expected one of {', '.join(SAFE_PROP_NAMES)})"
        )
    else:
        if prop != "--wp-safe-inset":
            warnings.append(
                f"uses {prop} but the spec names the property --wp-safe-inset (spec 06 section 3.3)"
            )
        # The property itself must resolve to vmin, not % or px.
        for declared in safe_property_values(text, prop):
            kind = unresolved_unit_for(text, declared)
            if kind == "percent":
                errors.append(
                    f"{prop}: {declared} uses a percentage; the safe-area inset must be in "
                    "vmin because percentage padding resolves against width and skews the "
                    "inset on non-square panels (spec 06 section 1.4)"
                )
            elif kind == "px":
                warnings.append(
                    f"{prop}: {declared} is an absolute length; the spec requires the inset to "
                    "scale with the panel"
                )
        # And anything consuming it must pass it straight through.
        for match in re.finditer(r"(?:padding\w*|inset)\s*:\s*([^;}]+)", text):
            value = match.group(1)
            if prop in value or "safe" in value:
                kind = unresolved_unit_for(text, value)
                if kind == "percent":
                    errors.append(
                        f"safe-area usage {value.strip()!r} resolves to a percentage; use vmin"
                    )

    # --- scrolling / zoom ---------------------------------------------------
    if not re.search(r"overflow\s*:\s*hidden", text, re.IGNORECASE):
        errors.append("does not set overflow: hidden; scrolling breaks touch mapping")
    if "viewport" in lower:
        meta = re.search(r'<meta[^>]*name=["\']viewport["\'][^>]*>', text, re.IGNORECASE)
        if meta and "user-scalable=no" not in meta.group(0).replace(" ", "").lower():
            warnings.append("viewport meta does not set user-scalable=no")

    # --- touch targets ------------------------------------------------------
    # Crude but useful: look for width/height declarations on button-ish rules.
    for match in re.finditer(r"\.([\w-]*btn[\w-]*|[\w-]*button[\w-]*)\s*\{([^}]*)\}", text, re.I):
        selector, body = match.group(1), match.group(2)
        dims = []
        for dim in ("width", "height", "min-width", "min-height"):
            m = re.search(rf"\b{dim}\s*:\s*([^;}}]+)", body)
            if m:
                dims.append(m.group(1).strip())
        for value in dims:
            vm = VMIN_RE.search(value)
            px = PX_RE.search(value)
            if vm:
                v = float(vm.group(1))
                mm = vmin_to_mm(v)
                if mm < MIN_TARGET_MM - 0.05:
                    errors.append(
                        f".{selector} {value} = {v:.1f}vmin = {mm:.2f} mm, below the "
                        f"{MIN_TARGET_MM} mm minimum ({MIN_TARGET_VMIN}vmin) (spec 06 section 3.6)"
                    )
            elif px:
                v = float(px.group(1))
                mm = v * REFERENCE_MM_PER_PX
                if mm < MIN_TARGET_MM - 0.05:
                    errors.append(
                        f".{selector} {value} = {mm:.2f} mm, below the {MIN_TARGET_MM} mm minimum"
                    )
            elif "%" in value:
                warnings.append(
                    f".{selector} sizes a touch target as {value!r}; percentages scale with the "
                    "panel and may fall under 7 mm on a small display"
                )

    # --- font sizes ---------------------------------------------------------
    for match in re.finditer(r"font-size\s*:\s*([\d.]+)\s*vmin", text):
        v = float(match.group(1))
        if v < 4.3 - 0.01:
            errors.append(
                f"font-size {v}vmin is below the 4.3vmin (=20 px at 466) minimum legible "
                "size (spec 06 section 3.5)"
            )

    return errors, warnings


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", type=Path, help="scene files (default: examples/watch-ui/*.html)")
    ap.add_argument("-q", "--quiet", action="store_true", help="only report failures")
    args = ap.parse_args()

    if args.paths:
        scenes = [p for p in args.paths if p.suffix.lower() in (".html", ".htm")]
    else:
        scenes = sorted(DEFAULT_SCENES.glob("*.html")) if DEFAULT_SCENES.exists() else []

    if not scenes:
        print("no scene files found", file=sys.stderr)
        return 1

    total_errors = 0
    for scene in scenes:
        errors, warnings = lint_scene(scene)
        total_errors += len(errors)
        rel = scene.relative_to(REPO) if scene.is_relative_to(REPO) else scene
        if not errors and not warnings:
            if not args.quiet:
                print(f"OK    {rel}")
            continue
        print(f"{'FAIL' if errors else 'warn'}  {rel}")
        for e in errors:
            print(f"        error: {e}")
        if not args.quiet:
            for w in warnings:
                print(f"        warn:  {w}")

    print()
    print(f"linted {len(scenes)} scene(s): {total_errors} error(s)")
    return 1 if total_errors else 0


if __name__ == "__main__":
    sys.exit(main())
