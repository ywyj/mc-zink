#!/usr/bin/env python3
"""Generate zoo1 per-feature unified-diff patches from pre-zoo snapshots.

BENCH-WINDOW safe: pure file IO, no compiler.

Input : backup/zoo_pre/{zink_screen.c,zink_kopper.c,zink_batch.c}
        current tree files (already carrying the zoo edits)
Output: patches/zoo1/0001-zoo1-infra.patch
        patches/zoo1/0002-p1-kopper-sync-present.patch
        patches/zoo1/0003-p2-batch-window.patch
        patches/zoo1/0004-p3-no-fb-wait.patch
        patches/zoo1/zoo1_all.patch
        patches/zoo1/final/{zink_screen.c,zink_kopper.c,zink_batch.c,zink_zoo.h}

Hunks are classified automatically by file name + marker text, so this
script does not duplicate the patched source text.
"""
import difflib, hashlib, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "mesa-26.2.5")
PRE = os.path.join(ROOT, "backup", "zoo_pre")
OUT = os.path.join(ROOT, "patches", "zoo1")
FINAL = os.path.join(OUT, "final")
REL = {
    "screen": "src/gallium/drivers/zink/zink_screen.c",
    "kopper": "src/gallium/drivers/zink/zink_kopper.c",
    "batch":  "src/gallium/drivers/zink/zink_batch.c",
    "header": "src/gallium/drivers/zink/zink_zoo.h",
}
ORDER = ["infra", "p1", "p2", "p3"]
NAMES = {
    "infra": "0001-zoo1-infra.patch",
    "p1": "0002-p1-kopper-sync-present.patch",
    "p2": "0003-p2-batch-window.patch",
    "p3": "0004-p3-no-fb-wait.patch",
}
REGISTRY = os.path.join(OUT, "registry.json")

def read(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()

def readlines(path):
    return read(path).splitlines(keepends=True)

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()

def compare(snap_lines, cur_lines):
    """Return list of hunks {i1,i2,lines} on the snapshot line axis."""
    sm = difflib.SequenceMatcher(None, snap_lines, cur_lines, autojunk=False)
    hooks = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        hooks.append({"i1": i1, "i2": i2, "lines": cur_lines[j1:j2]})
    return hooks

def reconstruct(snap_lines, hunks):
    out, pos, last = [], 0, 0
    for h in sorted(hunks, key=lambda x: x["i1"]):
        assert h["i1"] >= last, "overlapping hunks"
        out.extend(snap_lines[pos:h["i1"]])
        out.extend(h["lines"])
        pos = h["i2"]
        last = h["i2"]
    out.extend(snap_lines[pos:])
    return out

def classify(rel, hunk_lines):
    blob = "".join(hunk_lines)
    name = os.path.basename(rel)
    if name == "zink_kopper.c":
        return "p1"
    if name == "zink_batch.c":
        return "p2"
    if name == "zink_zoo.h":
        return "infra"
    if name == "zink_screen.c":
        if "!zink_zoo_no_fb_wait" in blob:
            return "p3"
        return "infra"
    raise SystemExit("unclassified file " + rel)

def unified(rel, a_lines, b_lines, fromfile=None, tofile=None):
    ff = fromfile if fromfile is not None else "a/" + rel
    tf = tofile if tofile is not None else "b/" + rel
    return "".join(difflib.unified_diff(a_lines, b_lines, fromfile=ff, tofile=tf, n=3))

def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(FINAL, exist_ok=True)
    # snapshot and current text for the three .c files
    snap = {}
    cur = {}
    hunks = []
    for key in ("screen", "kopper", "batch"):
        rel = REL[key]
        snap[rel] = readlines(os.path.join(PRE, os.path.basename(rel)))
        cur[rel] = readlines(os.path.join(SRC, rel))
        hs = compare(snap[rel], cur[rel])
        for h in hs:
            h["feature"] = classify(rel, h["lines"])
        hunks.extend([(rel, h) for h in hs])
        print("hunks %-40s %d  %s" % (rel, len(hs), [h["feature"] for h in hs]))
    # new header file: infra creates it; snapshot-side is empty
    header_rel = REL["header"]
    header_cur = readlines(os.path.join(SRC, header_rel))
    header_hunk = {"i1": 0, "i2": 0, "lines": header_cur, "feature": "infra", "create": True}
    hunks.append((header_rel, header_hunk))
    # per-file snapshot content; header starts absent
    snap[header_rel] = []
    cur[header_rel] = header_cur

    # states per cumulative feature set (entry () = pristine pre-zoo snapshot)
    states = {}
    states[()] = {rel: snap[rel] for rel in snap}
    fset = []
    for f in ORDER:
        fset = fset + [f]
        st = {}
        for rel in list(snap.keys()):
            hs = [h for r, h in hunks if r == rel and h["feature"] in fset]
            if rel == header_rel:
                if "infra" in fset:
                    st[rel] = header_cur
                else:
                    st[rel] = []
            else:
                st[rel] = reconstruct(snap[rel], hs)
        states[tuple(fset)] = st

    # verify final state byte-identical to the working tree
    final = states[tuple(ORDER)]
    for rel in final:
        if rel == header_rel:
            path = os.path.join(SRC, rel)
            assert read(path) == "".join(final[rel]), "header mismatch"
        else:
            path = os.path.join(SRC, rel)
            assert read(path) == "".join(final[rel]), "final state mismatch: " + rel
    print("verify: cumulative zoo state == working tree OK")

    # write final-state copies (used by zoo_apply.py)
    for rel in final:
        out_path = os.path.join(FINAL, os.path.basename(rel))
        with open(out_path, "w", encoding="utf-8", newline="") as f:
            f.write("".join(final[rel]))
        print("final", os.path.basename(rel), os.path.getsize(out_path), sha256(out_path))

    # per-feature patches: cumulative previous -> cumulative current
    prev = tuple()
    for f in ORDER:
        a = states[prev]
        b = states[prev + (f,)]
        chunks = []
        for rel in [REL["screen"], REL["kopper"], REL["batch"], REL["header"]]:
            a_lines = a.get(rel, [])
            b_lines = b.get(rel, [])
            if a_lines == b_lines:
                continue
            if not a_lines and b_lines:
                chunks.append(unified(rel, [], b_lines, fromfile="/dev/null", tofile="b/" + rel))
            else:
                chunks.append(unified(rel, a_lines, b_lines))
        text = "".join(chunks)
        assert text, "empty patch for " + f
        with open(os.path.join(OUT, NAMES[f]), "w", encoding="utf-8", newline="") as fh:
            fh.write(text)
        print("patch", NAMES[f], len(text), "bytes")
        prev = prev + (f,)

    # combined patch snapshot -> all
    a = states[tuple()]
    b = states[tuple(ORDER)]
    chunks = []
    for rel in [REL["screen"], REL["kopper"], REL["batch"], REL["header"]]:
        a_lines = a.get(rel, [])
        b_lines = b.get(rel, [])
        if a_lines == b_lines:
            continue
        if not a_lines and b_lines:
            chunks.append(unified(rel, [], b_lines, fromfile="/dev/null", tofile="b/" + rel))
        else:
            chunks.append(unified(rel, a_lines, b_lines))
    with open(os.path.join(OUT, "zoo1_all.patch"), "w", encoding="utf-8", newline="") as fh:
        fh.write("".join(chunks))
    print("patch zoo1_all.patch", sum(len(c) for c in chunks), "bytes")

    # registry: expected sha256 of pre and final states, for zoo_apply.py
    import json
    reg = {
        "tree": "src/mesa-26.2.5",
        "patch_order": ORDER,
        "patches": [NAMES[f] for f in ORDER],
        "pre": {rel: sha256(os.path.join(PRE, os.path.basename(rel))) for rel in [REL["screen"], REL["kopper"], REL["batch"]]},
        "final": {rel: sha256(os.path.join(FINAL, os.path.basename(rel))) for rel in final},
    }
    with open(REGISTRY, "w", encoding="utf-8") as fh:
        json.dump(reg, fh, indent=1, sort_keys=True)
        fh.write("\n")
    print("registry", REGISTRY)
    return 0

if __name__ == "__main__":
    sys.exit(main())
