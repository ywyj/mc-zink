# zoo2 P3 patch: remove the per-frame flush_frontbuffer batch wait (default-on)

Source: Mesa 26.2.5 (mesaopt cache-only baseline tree). One hunk, zink only.

Removes the upstream block in `zink_flush_frontbuffer` (src/gallium/drivers/zink/zink_screen.c):

    if (ctx->last_batch_state && screen->threaded_submit) {
       struct zink_batch_state *bs = ctx->last_batch_state;
       util_queue_fence_wait(&bs->flush_completed);
    }

Rationale: zoo1 measured `MESA_ZINK_NO_FB_WAIT=1` (same effect) at +3.9% and +3.4% fps in the
mix1 matrix (twice). Present ordering is still guaranteed by the kopper present queue, and the
upstream direction 33f0fecc removes a comparable wait.

Variant semantics: no env switch, no zink_zoo.h, always active; everything else = cache-only.

Apply order:

    cd <项目根目录>\mesaopt\src\mesa-26.2.5
    patch -p1 < ..\..\patches\zoo2\0001-p3-remove-frontbuffer-flush-wait.patch

(or use patches\layers\layer_ctl.py set zoo2 --tree <tree>, which is sha256-verified and reversible)

Snapshot: patches/layers/states/zoo2/files/src/gallium/drivers/zink/zink_screen.c
sha256 69fc48cc78af60288cb918f107a5aaec8271377e769413024450247ef639c2cb

Build: build\build_layer.bat zoo2 full -> dist\zoo2\opengl32.zoo2.dll (perf variant, not a
diagnostic layer). A/B against cache-only with no env, FG OFF.