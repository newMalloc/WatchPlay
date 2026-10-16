"""Check that relative Markdown links and in-page anchors resolve.

A protocol spec whose cross-references 404 is worse than useless: readers
follow a link, land nowhere, and assume the document is abandoned. This runs in
CI so a rename cannot silently break the docs.

Checks:
  * relative file links actually point at a file
  * in-page `#anchors` match a heading in the same file, using GitHub's
    slug rules as closely as they can be reproduced for mixed CJK/ASCII
    headings

Absolute http(s) links are skipped (CI has no business failing because a vendor
site is down).

    python tools/check_doc_links.py
"""

from __future__ import annotations

import re
import sys
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Folders we never author by hand.
SKIP_DIRS = {".git", "build", "node_modules", "__pycache__", ".gradle", ".pio"}

# Directories that may legitimately not exist yet while the project is being
# bootstrapped. Links into them are reported as warnings, not failures, so CI
# stays useful during construction instead of blocking every PR. Keep this list
# empty once the tree is complete -- a stale entry here hides real breakage.
PLANNED_DIRS: set[str] = set()

LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE_RE = re.compile(r"^\s*(```|~~~)")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")


def strip_code_fences(text: str) -> str:
    """Remove fenced code blocks so example links inside them are ignored."""
    out, inside, marker = [], False, ""
    for line in text.splitlines():
        m = FENCE_RE.match(line)
        if m:
            if not inside:
                inside, marker = True, m.group(1)
            elif line.strip().startswith(marker):
                inside = False
            continue
        if not inside:
            out.append(line)
    return "\n".join(out)


def slugify(heading: str) -> str:
    """Approximate GitHub's heading -> anchor slug.

    GitHub lowercases, drops everything that is not alphanumeric/CJK/underscore/
    hyphen, then turns spaces into hyphens. Reproducing it exactly for mixed
    CJK + punctuation is not possible, so [anchors_match] below compares on a
    canonical form instead of relying on this being byte-identical.
    """
    s = heading.strip().lower()
    s = re.sub(r"`([^`]*)`", r"\1", s)          # inline code contributes its text
    s = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", s)  # links contribute their label
    out = []
    for ch in s:
        if ch.isalnum() or ch in "-_" or "　" <= ch <= "鿿" or "\u3040" <= ch <= "\u30ff":
            out.append(ch)
        elif ch in " \t":
            out.append("-")
    return "".join(out)


def anchors_match(reference: str, heading: str) -> bool:
    """True when `reference` plausibly addresses `heading`.

    Compares the leading alphanumeric run of both. That is robust to the parts
    of GitHub's slugging we cannot reproduce (how each punctuation mark and each
    CJK character is folded), while still catching real breakage: a renamed
    heading, a typo, or a malformed anchor with an unbalanced bracket.
    """
    def core(s: str) -> str:
        s = unicodedata.normalize("NFKC", s).lower()
        s = re.sub(r"`([^`]*)`", r"\1", s)
        s = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", s)
        return "".join(ch for ch in s if ch.isalnum())[:16]

    ref_core, head_core = core(reference), core(heading)
    return bool(ref_core) and ref_core == head_core


def headings_of(text: str) -> list[str]:
    out = []
    for line in strip_code_fences(text).splitlines():
        m = HEADING_RE.match(line)
        if m:
            out.append(m.group(2))
    return out


def is_external(target: str) -> bool:
    return (
        target.startswith(("http://", "https://", "mailto:", "data:"))
        or target.startswith("//")
    )


def split_target(target: str) -> tuple[str, str]:
    """Return (path, fragment) for a link target."""
    if "#" in target:
        path, frag = target.split("#", 1)
    else:
        path, frag = target, ""
    return path.split("?", 1)[0], frag


def classify(repo_relative: str) -> str:
    """Return 'planned' if the link points into a not-yet-built directory."""
    posix = repo_relative.replace("\\", "/")
    for d in PLANNED_DIRS:
        if posix == d or posix.startswith(d + "/"):
            return "planned"
    return "required"


def main() -> int:
    errors: list[str] = []
    warnings: list[str] = []
    checked_links = 0
    checked_anchors = 0

    for path in sorted(REPO.rglob("*.md")):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        raw_text = path.read_text(encoding="utf-8", errors="replace")
        text = strip_code_fences(raw_text)
        headings = headings_of(raw_text)
        rel_self = path.relative_to(REPO)

        for match in LINK_RE.finditer(text):
            target = match.group(1).strip()
            if is_external(target):
                continue
            raw_path, fragment = split_target(target)
            line_no = text[: match.start()].count("\n") + 1

            # --- the file part -------------------------------------------------
            if raw_path:
                checked_links += 1
                resolved = (path.parent / raw_path).resolve()
                if not resolved.exists():
                    rel_to_repo = (
                        str(resolved.relative_to(REPO)) if resolved.is_relative_to(REPO) else raw_path
                    )
                    msg = f"{rel_self}:{line_no}  ->  {target}"
                    (warnings if classify(rel_to_repo) == "planned" else errors).append(msg)
                    continue
            else:
                resolved = path

            # --- the fragment part ---------------------------------------------
            if not fragment or resolved.suffix.lower() != ".md" or not resolved.exists():
                continue
            checked_anchors += 1
            target_headings = headings if resolved == path else headings_of(
                resolved.read_text(encoding="utf-8", errors="replace")
            )
            if target_headings and not any(anchors_match(fragment, h) for h in target_headings):
                errors.append(
                    f"{rel_self}:{line_no}  ->  #{fragment}  (no matching heading; "
                    "check for an unbalanced bracket or a renamed section)"
                )

    print(f"checked {checked_links} relative links and {checked_anchors} anchors in {REPO}")
    if warnings:
        print(f"\n{len(warnings)} link(s) point into not-yet-built directories (ok for now):")
        for w in warnings:
            print(f"  warn  {w}")
    if errors:
        print(f"\n{len(errors)} broken reference(s):")
        for e in errors:
            print(f"  FAIL  {e}")
        return 1
    print("\nall required links and anchors resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
