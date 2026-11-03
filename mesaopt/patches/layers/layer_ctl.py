#!/usr/bin/env python3
"""[dsh-mesaopt] Layer state switcher for layer-bisection builds.

States (see states.json):
  cacheonly   = pristine Mesa 26.2.5 + disk-cache/fat-opengl32 patches only
  cachezoo    = cacheonly + zoo patches P1/P2/P3 (current tree, empirically good)
  z0only      = cacheonly + Z0 fence-timing patch
  glcostonly  = cacheonly + GLCOST instrumentation layer

Usage:
  python layer_ctl.py status [--tree DIR]
  python layer_ctl.py set <state> [--tree DIR] [--force] [--dry-run]
  python layer_ctl.py undo [--tree DIR]

The tool only touches the 21 managed files listed in states.json and never runs a
build. Switching is sha256-verified; it refuses to overwrite a tree that matches
no known state unless --force is given. Every set copies pre-images to
.undo/<timestamp>/ and records the transition in .last.json (undo restores it).
"""
import hashlib, json, os, shutil, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TREE = r"<项目根目录>\mesaopt\src\mesa-26.2.5"


def load_meta():
    with open(os.path.join(HERE, "states.json"), "r", encoding="utf-8") as f:
        meta = json.load(f)
    with open(os.path.join(HERE, "shas.json"), "r", encoding="utf-8") as f:
        shas = json.load(f)
    return meta, shas


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def expected(meta, shas, state):
    exp = {}
    overlay = meta["states"][state]
    for p in meta["managed"]:
        if p in overlay:
            exp[p] = (shas[state][p], os.path.join(HERE, overlay[p]))
        elif p in meta["pristine"]:
            exp[p] = (shas["pristine"][p], os.path.join(HERE, meta["pristine"][p]))
        else:
            exp[p] = (None, None)
    return exp


def current(meta, tree):
    cur = {}
    for p in meta["managed"]:
        dst = os.path.join(tree, p)
        cur[p] = sha256_file(dst) if os.path.isfile(dst) else None
    return cur


def detect(meta, shas, tree):
    cur = current(meta, tree)
    hits = []
    for st in meta["states"]:
        exp = expected(meta, shas, st)
        if all(cur[p] == exp[p][0] for p in meta["managed"]):
            hits.append(st)
    return hits, cur


def set_state(meta, shas, tree, state, force, dry, touch_all=False):
    if state not in meta["states"]:
        print("unknown state %r; known: %s" % (state, ", ".join(meta["states"])))
        return 2
    hits, cur = detect(meta, shas, tree)
    if not hits and not force:
        print("REFUSED: tree matches no known state (use --force to overwrite).")
        for st in meta["states"]:
            exp = expected(meta, shas, st)
            bad = [p for p in meta["managed"] if cur[p] != exp[p][0]]
            print("  %-11s differs in %d/%d: %s" % (st, len(bad), len(meta["managed"]), ", ".join(bad[:4])))
        return 2
    exp = expected(meta, shas, state)
    changed = [(p, cur[p], exp[p][0]) for p in meta["managed"] if cur[p] != exp[p][0]]
    print("set %s: %d file(s) change (current state: %s)" % (state, len(changed), ",".join(hits) or "unknown"))
    for p, a, b in changed:
        print("  %-52s %s -> %s" % (p, (a or "absent")[:12], (b or "absent")[:12]))
    if dry:
        print("dry-run: nothing written")
        return 0
    stamp = time.strftime("%Y%m%d-%H%M%S")
    bdir = os.path.join(HERE, ".undo", stamp)
    for p, a, b in changed:
        dst = os.path.join(tree, p)
        if a is not None:
            bak = os.path.join(bdir, p)
            os.makedirs(os.path.dirname(bak), exist_ok=True)
            shutil.copy2(dst, bak)
        if b is None:
            if os.path.isfile(dst):
                os.remove(dst)
        else:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(exp[p][1], dst)
            # copy2 preserves the snapshot mtime; ninja compares mtimes, so a
            # restored file that is older than the object built from the previous
            # layer state would be silently treated as up-to-date.  Touch it so the
            # next ninja run really recompiles the changed TU.
            os.utime(dst, None)
    if touch_all:
        # Objects in build/ may have been compiled from a third layer state whose
        # mtime was newer than the snapshot files restored by an intermediate
        # switch; touching every managed file forces ninja to rebuild them all.
        n = 0
        for p in meta["managed"]:
            dst = os.path.join(tree, p)
            if os.path.isfile(dst):
                os.utime(dst, None)
                n += 1
        print("touch-all: refreshed mtime of %d managed file(s)" % n)
    rec = {"time": stamp, "from": hits, "to": state,
           "changed": [p for p, _, _ in changed], "undo_dir": bdir}
    with open(os.path.join(HERE, ".last.json"), "w", encoding="utf-8") as f:
        json.dump(rec, f, indent=1)
    print("OK -> %s (pre-images: %s)" % (state, bdir))
    return 0


def undo(meta, tree):
    last = os.path.join(HERE, ".last.json")
    if not os.path.isfile(last):
        print("nothing to undo (.last.json missing)")
        return 2
    with open(last, "r", encoding="utf-8") as f:
        rec = json.load(f)
    bdir = rec["undo_dir"]
    for p in rec["changed"]:
        dst = os.path.join(tree, p)
        bak = os.path.join(bdir, p)
        if os.path.isfile(bak):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(bak, dst)
            os.utime(dst, None)
            print("restored " + p)
        elif os.path.isfile(dst):
            os.remove(dst)
            print("removed " + p)
    os.remove(last)
    print("undo OK")
    return 0


def main(argv):
    args = argv[1:]
    tree = DEFAULT_TREE
    if "--tree" in args:
        i = args.index("--tree")
        tree = args[i + 1]
        del args[i:i + 2]
    force = "--force" in args
    dry = "--dry-run" in args
    touch_all = "--touch-all" in args
    args = [a for a in args if a not in ("--force", "--dry-run", "--touch-all")]
    meta, shas = load_meta()
    if not args or args[0] == "status":
        hits, cur = detect(meta, shas, tree)
        print("tree : " + tree)
        print("state: " + (",".join(hits) if hits else "UNKNOWN"))
        if not hits:
            for st in meta["states"]:
                exp = expected(meta, shas, st)
                bad = [p for p in meta["managed"] if cur[p] != exp[p][0]]
                print("  %-11s differs in %d file(s): %s" % (st, len(bad), ", ".join(bad[:4])))
        return 0
    if args[0] == "set":
        if len(args) < 2:
            print("usage: layer_ctl.py set <state> [--tree DIR] [--force] [--dry-run] [--touch-all]")
            return 2
        return set_state(meta, shas, tree, args[1], force, dry, touch_all)
    if args[0] == "undo":
        return undo(meta, tree)
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
