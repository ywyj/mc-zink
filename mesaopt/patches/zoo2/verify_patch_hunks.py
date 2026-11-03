#!/usr/bin/env python3
"""Statically verify that one or more git patches still apply textually to a
source tree (no compiler involved).

For every hunk we take the old side (context + removed lines) as an exact line
block and require it to occur exactly once in the target file.  We also report
the line-number offset vs the patch header so a patch that is merely shifted is
still visible.

Usage:
  python verify_patch_hunks.py <tree> <patch> [<patch> ...]
Exit code 0 = every hunk matched exactly once.
"""
import os, re, sys

HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
FILE = re.compile(r"^diff --git a/(\S+) b/(\S+)")

def read_lines(path):
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
        return f.read().splitlines()

def parse_patch(path):
    """Return list of (rel_path, hunks); hunk = dict(old_start, old_count,
    old_lines, new_lines, entries)."""
    out = []
    cur_file = None
    cur_hunks = []
    hunk = None
    for line in read_lines(path):
        mo = FILE.match(line)
        if mo:
            if cur_file is not None:
                out.append((cur_file, cur_hunks))
            cur_file = mo.group(2)
            cur_hunks = []
            hunk = None
            continue
        if line.startswith(("--- ", "+++ ", "index ", "new file", "deleted file",
                            "similarity ", "rename ")):
            continue
        mo = HUNK.match(line)
        if mo:
            hunk = {"old_start": int(mo.group(1)),
                    "old_count": int(mo.group(2)) if mo.group(2) is not None else 1,
                    "old_lines": [], "new_lines": [], "entries": [], "removed": 0}
            cur_hunks.append(hunk)
            continue
        if hunk is None:
            continue
        if line == "-- ":   # git format-patch signature separator, not patch content
            hunk = None
            continue
        if len(hunk["old_lines"]) >= hunk["old_count"]:   # hunk already complete
            hunk = None
            continue
        if line.startswith("\\"):
            continue
        if line.startswith(" ") or line == "":
            txt = line[1:]
            hunk["old_lines"].append(txt)
            hunk["new_lines"].append(txt)
            hunk["entries"].append(("c", txt))
        elif line.startswith("-"):
            txt = line[1:]
            hunk["old_lines"].append(txt)
            hunk["removed"] += 1
            hunk["entries"].append(("-", txt))
        elif line.startswith("+"):
            hunk["new_lines"].append(line[1:])
            hunk["entries"].append(("+", line[1:]))
        else:
            hunk = None
    if cur_file is not None:
        out.append((cur_file, cur_hunks))
    return out

def find_block(file_lines, block):
    n = len(block)
    if n == 0:
        return []
    hits = []
    first = block[0]
    for i in range(0, len(file_lines) - n + 1):
        if file_lines[i] != first:
            continue
        if file_lines[i:i + n] == block:
            hits.append(i)
    return hits

def main():
    if len(sys.argv) < 3:
        print("usage: verify_patch_hunks.py <tree> <patch> [<patch> ...]")
        return 2
    tree = sys.argv[1]
    rc = 0
    for patch_path in sys.argv[2:]:
        print("== %s ==" % patch_path)
        total = ok = shifted = 0
        for rel, hunks in parse_patch(patch_path):
            target = os.path.join(tree, rel)
            if not os.path.isfile(target):
                print("  MISSING FILE", rel)
                rc = 1
                continue
            flines = read_lines(target)
            for idx, h in enumerate(hunks):
                total += 1
                hits = find_block(flines, h["old_lines"])
                want = h["old_start"] - 1
                if len(hits) == 1:
                    ok += 1
                    off = hits[0] - want
                    if off:
                        shifted += 1
                        print("  hunk %d %-60s MATCH (shifted %+d lines)" % (idx + 1, rel, off))
                    else:
                        print("  hunk %d %-60s MATCH" % (idx + 1, rel))
                elif len(hits) == 0:
                    rc = 1
                    print("  hunk %d %-60s MISSING (old side not found)" % (idx + 1, rel))
                    for ln in h["old_lines"][:8]:
                        print("        expected:", ln)
                else:
                    rc = 1
                    print("  hunk %d %-60s AMBIGUOUS (%d matches)" % (idx + 1, rel, len(hits)))
        print("  -- %s: %d/%d hunks match (%d shifted)" % (os.path.basename(patch_path), ok, total, shifted))
    return rc

if __name__ == "__main__":
    sys.exit(main())
