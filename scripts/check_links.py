#!/usr/bin/env python3
"""Checks every Markdown link in the repository (Python standard library only).

  python3 scripts/check_links.py

For each *.md file (outside node_modules, .venv, dist and out): every relative link must
point at an existing file or directory, and every #anchor must match a heading in the
target file (GitHub's rules: lower-case; punctuation and emoji dropped; spaces become
hyphens; a repeated heading gets -1, -2, ...). External http(s) and mailto links are listed
but not fetched, so the check works offline. It also checks that every file the
documentation index (documentation/README.md) lists exists. Exit code 1 on any broken link.
"""

import os
import re
import sys
import urllib.parse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SKIP_DIRS = {"node_modules", ".venv", "dist", "out", ".git", "__pycache__"}
LINK = re.compile(r"(?<!!)\[(?:[^\[\]]|\[[^\]]*\])*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
IMAGE = re.compile(r"!\[[^\]]*\]\(([^)\s]+)\)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
FENCE = re.compile(r"^\s*(```|~~~)")


def markdown_files():
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for f in filenames:
            if f.endswith(".md"):
                yield os.path.join(dirpath, f)


def slug(text):
    text = re.sub(r"`([^`]*)`", r"\1", text)          # code spans keep their text
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)  # links keep their text
    text = re.sub(r"<[^>]+>", "", text)
    text = text.strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)               # drops punctuation and emoji
    return text.replace(" ", "-")


def anchors(path, cache={}):
    if path not in cache:
        found, counts, in_fence = set(), {}, False
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                if FENCE.match(line):
                    in_fence = not in_fence
                    continue
                if in_fence:
                    continue
                m = HEADING.match(line)
                if m:
                    s = slug(m.group(2))
                    n = counts.get(s, 0)
                    counts[s] = n + 1
                    found.add(s if n == 0 else f"{s}-{n}")
                for a in re.findall(r'<a\s+(?:name|id)="([^"]+)"', line):
                    found.add(a)
        cache[path] = found
    return cache[path]


def links_in(path):
    in_fence = False
    with open(path, encoding="utf-8") as fh:
        for no, line in enumerate(fh, 1):
            if FENCE.match(line):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            stripped = re.sub(r"`[^`]*`", "", line)  # a link inside a code span is not a link
            for m in list(LINK.finditer(stripped)) + list(IMAGE.finditer(stripped)):
                yield no, m.group(1)


def main():
    broken, checked, external = [], 0, set()
    files = sorted(markdown_files())
    for path in files:
        rel = os.path.relpath(path, ROOT)
        for no, target in links_in(path):
            if re.match(r"^(https?:|mailto:)", target):
                external.add(target)
                continue
            checked += 1
            target = urllib.parse.unquote(target)
            file_part, _, anchor = target.partition("#")
            dest = path if file_part == "" else os.path.normpath(os.path.join(os.path.dirname(path), file_part))
            if not os.path.exists(dest):
                broken.append(f"{rel}:{no}: {target} -> missing {os.path.relpath(dest, ROOT)}")
                continue
            if anchor and dest.endswith(".md") and anchor not in anchors(dest):
                broken.append(f"{rel}:{no}: {target} -> no heading #{anchor} in {os.path.relpath(dest, ROOT)}")
    print(f"{len(files)} Markdown files, {checked} relative links checked, {len(external)} external links not fetched")
    for b in broken:
        print("BROKEN  " + b)
    print(f"links: {'all good' if not broken else str(len(broken)) + ' broken'}")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
