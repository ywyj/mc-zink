#!/usr/bin/env python3
"""Apply/revert the zoo1 source edits on a mesa-26.2.5 build tree.

The canonical artifact is patches/zoo1/*.patch (unified diffs, generated from
backup/zoo_pre snapshots by make_zoo_patches.py).  This helper is the
convenience path used inside the build tree: it verifies sha256 of every file
before touching it, so it refuses to run on a tree that already drifted.

Usage:
  python zoo_apply.py check   [--tree DIR]
  python zoo_apply.py apply   [--tree DIR]   # pristine -> zoo1 with all patches
  python zoo_apply.py revert  [--tree DIR]   # zoo1 -> pristine
  python zoo_apply.py status  [--tree DIR]

Default tree: <mesaopt>/src/mesa-26.2.5
"""
import argparse, hashlib, json, os, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ZOO1 = os.path.join(HERE, "zoo1")
ROOT = os.path.dirname(HERE)
PRE = os.path.join(ROOT, "backup", "zoo_pre")
FINAL = os.path.join(ZOO1, "final")
REL = [
    "src/gallium/drivers/zink/zink_screen.c",
    "src/gallium/drivers/zink/zink_kopper.c",
    "src/gallium/drivers/zink/zink_batch.c",
]
HEADER = "src/gallium/drivers/zink/zink_zoo.h"

def sha256(path):
    if not os.path.exists(path):
        return "<absent>"
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()

def load_registry():
    with open(os.path.join(ZOO1, "registry.json"), "r", encoding="utf-8") as f:
        return json.load(f)

def state_of(tree, reg):
    """Classify tree as 'pre', 'final' or 'drift'; returns (state, details)."""
    details = {}
    for rel in REL + [HEADER]:
        path = os.path.join(tree, rel)
        got = sha256(path)
        want_final = reg["final"].get(rel, "<absent>")
        want_pre = reg["pre"].get(rel, "<absent>")
        if got == want_final:
            details[rel] = "final"
        elif got == want_pre:
            details[rel] = "pre"
        else:
            details[rel] = "drift(got=%s)" % got
    vals = set(details.values())
    if vals == {"final"}:
        return "final", details
    if vals == {"pre"}:
        return "pre", details
    return "drift", details

def copy_file(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    tmp = dst + ".zoo_tmp"
    shutil.copy2(src, tmp)
    os.replace(tmp, dst)

def do_apply(tree, reg):
    st, details = state_of(tree, reg)
    if st == "final":
        print("already zoo1-final: nothing to apply")
        return 0
    if st != "pre":
        print("REFUSING apply: tree is neither pristine-z0v1 nor zoo1-final")
        for k, v in details.items():
            print("  ", k, v)
        return 2
    for rel in REL + [HEADER]:
        copy_file(os.path.join(FINAL, os.path.basename(rel)), os.path.join(tree, rel))
        print("applied", rel)
    st2, _ = state_of(tree, reg)
    print("state after apply:", st2)
    return 0 if st2 == "final" else 3

def do_revert(tree, reg):
    st, details = state_of(tree, reg)
    if st == "pre":
        print("already pristine: nothing to revert")
        return 0
    if st != "final":
        print("REFUSING revert: tree is neither zoo1-final nor pristine")
        for k, v in details.items():
            print("  ", k, v)
        return 2
    for rel in REL:
        copy_file(os.path.join(PRE, os.path.basename(rel)), os.path.join(tree, rel))
        print("reverted", rel)
    hdr = os.path.join(tree, HEADER)
    if os.path.exists(hdr):
        os.remove(hdr)
        print("removed", HEADER)
    st2, _ = state_of(tree, reg)
    print("state after revert:", st2)
    return 0 if st2 == "pre" else 3

def do_check(tree, reg):
    st, details = state_of(tree, reg)
    print("state:", st)
    for k in REL + [HEADER]:
        print("  %-55s %s" % (k, details[k]))
    return 0 if st in ("pre", "final") else 2

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["check", "apply", "revert", "status"])
    ap.add_argument("--tree", default=os.path.join(ROOT, "src", "mesa-26.2.5"))
    a = ap.parse_args()
    reg = load_registry()
    if a.cmd == "status":
        a.cmd = "check"
        print("tree:", a.tree)
    if a.cmd == "check":
        return do_check(a.tree, reg)
    if a.cmd == "apply":
        return do_apply(a.tree, reg)
    return do_revert(a.tree, reg)

if __name__ == "__main__":
    sys.exit(main())
