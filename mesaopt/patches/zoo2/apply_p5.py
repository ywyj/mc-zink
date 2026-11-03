#!/usr/bin/env python3
"""Apply or revert the zoo2/P5 upstream patches on the 26.2.5 tree.

Unlike "git apply" this works on raw bytes: it locates the exact old-side line
block of every hunk (uniqueness is asserted) and replaces it with the new-side
block, so LF line endings and file bytes outside the hunks are preserved.

Patches are applied in upstream order (see PATCH_ORDER): they were authored
sequentially on top of each other.

Usage:
  python apply_p5.py check  [--tree DIR]
  python apply_p5.py apply  [--tree DIR]
  python apply_p5.py revert [--tree DIR]
Default tree: <mesaopt>/src/mesa-26.2.5
"""
import argparse, hashlib, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from verify_patch_hunks import parse_patch   # noqa: E402

ROOT = os.path.dirname(os.path.dirname(HERE))
PATCH_ORDER = ["1ca365b2.patch", "5710cae1.patch", "32658eec.patch"]
def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()

def block_bytes(lines):
    return "".join(ln + "\n" for ln in lines).encode("utf-8")

def apply_patch(tree, patch_path, reverse):
    rc = 0
    for rel, hunks in parse_patch(patch_path):
        path = os.path.join(tree, rel)
        if not os.path.isfile(path):
            print("MISSING FILE", rel)
            return 1
        with open(path, "rb") as f:
            data = f.read()
        for idx, h in enumerate(hunks):
            old_b = block_bytes(h["old_lines"])
            new_b = block_bytes(h["new_lines"])
            src, dst = (new_b, old_b) if reverse else (old_b, new_b)
            n = data.count(src)
            if n != 1:
                print("FAIL hunk %d %s: expected exactly 1 match, got %d (file unchanged)" % (idx + 1, rel, n))
                rc = 2
                continue
            data = data.replace(src, dst, 1)
            print("%s hunk %d %s %s" % ("REVERT" if reverse else "APPLY", idx + 1, rel,
                                        "ok (%d bytes)" % (len(dst) - len(src))))
        with open(path, "wb") as f:
            f.write(data)
    return rc

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["check", "apply", "revert"])
    ap.add_argument("--tree", default=os.path.join(ROOT, "src", "mesa-26.2.5"))
    ap.add_argument("--expect", choices=["applied", "pristine"],
                    help="check only: fail unless every hunk is in the given state")
    a = ap.parse_args()
    if a.cmd == "check":
        rc = 0
        n_pristine = n_applied = n_unknown = 0
        for p in PATCH_ORDER:
            print("== %s ==" % p)
            for rel, hunks in parse_patch(os.path.join(HERE, p)):
                with open(os.path.join(a.tree, rel), "rb") as f:
                    data = f.read()
                for idx, h in enumerate(hunks):
                    old_b = block_bytes(h["old_lines"])
                    new_b = block_bytes(h["new_lines"])
                    if data.count(old_b) == 1:
                        print("  hunk %d %s: pristine side OK" % (idx + 1, rel))
                        n_pristine += 1
                    elif data.count(new_b) == 1:
                        print("  hunk %d %s: ALREADY APPLIED" % (idx + 1, rel))
                        n_applied += 1
                    else:
                        print("  hunk %d %s: UNKNOWN state (neither side matches once)" % (idx + 1, rel))
                        n_unknown += 1
                        rc = 2
        print("SUMMARY pristine=%d applied=%d unknown=%d" % (n_pristine, n_applied, n_unknown))
        if a.expect == "applied" and not (n_applied > 0 and n_pristine == 0 and n_unknown == 0):
            print("EXPECT-FAIL: --expect applied but tree is not fully P5-applied")
            return 1
        if a.expect == "pristine" and not (n_pristine > 0 and n_applied == 0 and n_unknown == 0):
            print("EXPECT-FAIL: --expect pristine but tree is not fully pristine")
            return 1
        return rc
    order = PATCH_ORDER if a.cmd == "apply" else list(reversed(PATCH_ORDER))
    rc = 0
    for p in order:
        print("== %s %s ==" % (a.cmd.upper(), p))
        rc |= apply_patch(a.tree, os.path.join(HERE, p), reverse=(a.cmd == "revert"))
    return rc

if __name__ == "__main__":
    sys.exit(main())
