# ZINK FPS 路径评估（task-8 子项 3，不改代码优先）

> 作者：mesaopt；依据：Mesa 26.2.5 完整源码（<项目根目录>/mesaopt/src/mesa-26.2.5）+ 已有 A/B 实测（cfgpack/bench D~K）。
> 所有收益数字标注 **[未验证]**，只有源码位置和机制是已取证的。

## 0. 结论摘要（TL;DR）

1. **env-only 路线基本挖干了**：ZINK_DEBUG/GPL/descriptor 三个方向 cfgpack 已测（D~K），全部不如基线或持平。env 里只剩一个**真没测过**的：`ZINK_INLINE_UNIFORMS=1`（默认 dGPU=off，开对 uniform 多的光影/钠系渲染可能省 descriptor 更新），预期 +0~3% [未验证]，成本=一次 bench。
2. **代码级最大机会不是 zink 本身，而是「Windows WGL 前端没有 glthread」**：全源码唯一 `_mesa_glthread_init()` 调用点在 `src/gallium/frontends/dri/dri_context.c:236`，`src/gallium/frontends/wgl` 里 glthread 关键字 **0 命中** → Lead 之前 `_bench_B_glthread.bat` 的 `set mesa_glthread=true` 对 zink/WGL 是**纯 no-op**。要拿到 glthread 必须改 Mesa 源码（stw 前端加 init + 同步点）重编 DLL。这是 CPU-bound MC 最可能拿到两位数百分比的一项，风险中高。
3. **zink 每帧已有一个可量化的同步点**：`zink_screen.c:1930-1933` 在 present 前 `util_queue_fence_wait(&bs->flush_completed)`（渲染线程等提交线程），是「每帧一次」的固定 CPU 停顿；去掉/后置需要改 present 顺序，预期 +0~2% [未验证]，风险低-中。
4. descriptor 缓存 / renderpass 跟踪 / 提交批处理：**机制已在最优配置附近**（auto descriptor + rp on + threaded_submit on），源码级继续优化的空间小、工作量大（周级），不建议作为当前 P0。
5. 编译选项（/O3、LTO、/arch:AVX2）诚实预期 **+0~2%** [未验证]，一个全量重编（~40min）才能验证，性价比低，放在最后。

## 1. 已测结论（勿重复）

| 变体 | 设置 | 结果 vs 基线 |
|---|---|---|
| 基线 | auto descriptor + rp + threaded_submit | Complementary 光影 zink ~100.2 fps（装 dshconfig+createopt 后） |
| D | ZINK_DESCRIPTORS=auto | 基线本身 |
| E | ZINK_DESCRIPTORS=db | 97.2（更差，-3%） |
| F | ZINK_DESCRIPTORS=lazy | 与 auto 持平 |
| G | ZINK_DEBUG=gpl | 96.3（更差） |
| H | ZINK_DEBUG=... inline uniforms off | 基线默认就是 off（dGPU），H 等于复测基线 |
| I | MESA_GL_VERSION_OVERRIDE=4.6 | 无效（已是 4.6） |
| J | ZINK_DEBUG=rp | 与默认 rp 持平 |
| K | ZINK_DEBUG=norp | 更差（renderpass 拆分变多） |
| BF(在 bench 脚本里) | mesa_glthread=true | **no-op**（WGL 前端无 glthread，见 §2） |

**GLCOUNT 实测调用数（无光影/SR 关/1080p、zink；Lead 03:1x 采集，电池期 FPS 无效但调用数有效；来源 `<项目根目录>/shaders/GLCOUNT_RESULTS_g1_noshader.md`）**
- 稳态（尾部 100 帧）：**draws 89/帧**、total GL calls **2,118/帧**、program_binds 141、tex_binds 533、buf_binds 223、viewport 103、clears 19、fbo_binds 24、barriers 9、mipmaps 0、**flushes 0.1 / finishes 0 / readback 0**。
- Top 调用点：glPixelStorei 176、glBindTexture 167、glTexParameteri 135、glUniform1fv 116、glActiveTexture 105、glUseProgram 99、glUniformMatrix4fv 87、glBindBuffer 74；另有 AcceleratedRendering 每帧 **~90 次 glGetIntegeri**（582/584 个稳态帧恰好 ~90 次，与 draws 同量级）。
- 判读：无光影下**不是 draw-call bound**；CPU 侧大头是纹理/状态族（>950 次/帧）与状态查询（~90 次/帧）——两者都是 zink 上每条一次 GL dispatch 的 CPU 成本。

## 2. P1：给 Windows WGL 前端补 glthread（最大机会，需改代码）

### 机制（已取证）
- glthread 在 GL dispatch 层把 `glDraw*`/状态设置等调用 marshal 进命令缓冲，由独立 worker 线程解编执行，渲染线程只做 memcpy 级工作。[Mesa main/glthread.c 文件头注释]
- 前置条件：`src/mesa/main/glthread.c:211-219` `_mesa_glthread_init()` 要求 `screen->caps.map_unsynchronized_thread_safe && allow_mapped_buffers_during_execution`；zink 两个都置 true：`src/gallium/drivers/zink/zink_screen.c:792-793`。**zink 完全具备 glthread 条件。**
- 启用策略（DRI 前端）：`src/gallium/frontends/dri/dri_context.c:204-236`：driver 默认(`mesa_glthread_driver`，zink 的 driconf 默认 true 见 `src/gallium/drivers/zink/driinfo_zink.h:9`) → app profile → 用户 env `mesa_glthread`；且 `nr_cpus<4` 或 `nr_big_cpus<5` 时默认关（本机 5800H 16 线程、8 大核 → 会开）。
- **WGL 缺失**：`stw_context.c` 的 `stw_create_context`（`src/gallium/frontends/wgl/stw_context.c:248` `st_api_create_context(...)`）创建完 st context 后没有任何 glthread 初始化；`grep -r "_mesa_glthread_init(" src/` 只有 dri_context.c:236 一处。
- 既有 `_bench_B_glthread.bat` 只是 `set mesa_glthread=true`，因为没有 init 调用点，**这个 env 在 zink/WGL 上被完全忽略**（即使 glthread.c 里没有 WGL 侧读取代码）。
- **GLCOUNT 量化（§1）**：glthread 要覆盖的正是「纹理/状态族 ≈950 次/帧」，而 draws 只有 89/帧 → 若 glthread 生效，理论收益面比 draw 批处理大得多；这也是 zink 每帧比原生 GL 多 ~4.5ms 的主要候选（量级上说得通，但**未实测**，且 glthread 会改变同步语义，风险中高）。

### 补丁设计（未实现，待 Lead 批准窗口）
1. `src/gallium/frontends/wgl/stw_context.c`：在 `ctx->st = st_api_create_context(...)`（:248）成功、`ctx->st->frontend_context = ctx`（:253）之后：
   ```c
   #include "main/glthread.h"   /* 文件头 */
   /* 仅当显式要求或默认开启时 */
   if (debug_get_bool_option("MESA_GLTHREAD", true))
      _mesa_glthread_init(ctx->st->ctx);
   ```
   （注意 `stw_dev->st_options`/driconf 在此处可用；也可复用 `stw_dev->option_cache` 的 `mesa_glthread_driver` 选项，保持一致语义。）
2. 同步点（必须全部处理，否则 use-after-free/花屏）：
   - `stw_wgl.c:103 wglMakeCurrent` → `DrvSetContext`（`stw_context.c` 内实现）切换当前 context 前，对**旧的当前 context** 调 `_mesa_glthread_finish(old->st->ctx)`（DRI 等价点：`dri_context.c:255/279/320`）。
   - `stw_framebuffer.c:733 stw_framebuffer_swap_locked`（wglSwapBuffers 入口）开头调 `_mesa_glthread_finish(ctx->st->ctx)`（DRI 等价点：`dri/kopper.c:191/409/602/622` 在 swap/present 前 finish）。
   - `stw_context.c:299 stw_destroy_context`／`DrvDeleteContext`：`st_destroy_context` 内部已调 `_mesa_glthread_destroy`（`st_context.c:896`），但要确认销毁前当前 context 已 finish（删除当前 context 的路径 DRI 也有 finish，`dri_context.c:255`）。
   - `wglGetProcAddress`/`DrvGetProcAddress` 不需要（不走 dispatch）。
3. 风险：stw 前端从未在异步 dispatch 下运行过；Iris/Sodium 会大量 `glGet*`、`glMapBuffer*`、`glReadPixels`、FBO 状态查询，glthread 对这些有 marshal 同步（`_mesa_glthread_finish`），但 Windows/WGL 特有的 `wglSwapBuffers`/`wglMakeCurrent` 路径最容易漏同步 → 必须先在**无光影→光影→切分辨率/全屏**三类场景冒烟，再跑 bench。
4. 工作量：补丁 ~80-120 行（1 个文件新 include + 3-4 个同步点），1 次增量编译（stw 前端 + 重链，约 2-5min），1-2 轮 bench。
5. 预期：DRI 下 glthread 在 CPU-bound 应用常见 +10~40%；MC 渲染线程本身还有 Java 侧开销，保守 **+5~15%** [未验证]，收益呈现在平均 fps 与 1% low 上（渲染线程不再是 GPU 提交的串行瓶颈）。

## 3. P2：present 前每帧 fence wait（低风险小收益）

> GLCOUNT 补充：游戏每帧 `glFlush` 只有 0.1 次 → 帧边界由 `wglSwapBuffers` 触发的 `ST_FLUSH_END_OF_FRAME` 产生，**每帧 1 次 flush 不可避免**；P2 省掉的只是 flush 之后 `util_queue_fence_wait(&bs->flush_completed)` 的那段等待（`zink_screen.c:1930-1933`），所以天花板很低。

- 位置：`src/gallium/drivers/zink/zink_screen.c:1926-1934`：`zink_flush_frontbuffer()` 里 `pctx->flush(pctx,NULL,PIPE_FLUSH_END_OF_FRAME)` 之后，`if (ctx->last_batch_state && screen->threaded_submit) util_queue_fence_wait(&bs->flush_completed);`。
- 作用：保证 zink_end_batch 在 flush_queue 线程上完成（`res->obj->present` 信号量在此创建），之后 `zink_kopper_present_queue`（`zink_kopper.c:869`）把它作为 present 的 wait semaphore 入队（`zink_kopper.c:950-955` 异步 present 到同一 flush_queue）。
- 可优化方向：把 present 的提交也变成 flush_queue 的**串行后续任务**，用现有队列顺序保证而不是渲染线程阻塞等待；或仅当 `!res->obj->present` 时才 wait。预期省每次 present 前 10~200µs（取决于 submit 队列深度）→ **+0~2%** [未验证]。
- 风险：present 顺序错会黑屏/花屏；改动在 kopper 核心路径，需要 32/64 位、全屏/窗口、光影开关多场景验证。工作量 1-2 天。

## 4. P3：descriptor 缓存 / renderpass 跟踪（机制已接近最优，工作量大）

- `ZINK_DESCRIPTORS` 解析：`zink_screen.c:3781-3790`，auto 在本机 NVIDIA 上大概率=db；但 E 实测 db 更差（-3%），说明当前负载下 descriptor buffer 的内存/更新开销 > 省下的绑定开销。**没有 env 级新空间。**
- renderpass 跟踪：`ZINK_DEBUG=rp/norp` 已测（J 持平、K 更差）；`rploads`/`rpstores` 两个子开关未测，预期 ±1% [未验证]，可作为一次低成本 bench 的附带变体。
- 代码级：descriptor 集复用已有（per-program cache），继续优化需要改 `zink_descriptors.c` 的 hash/更新粒度，收益不确定、工作周级 → 不建议。

## 5. P4：提交批处理 / 线程提交（已是 threaded，空间小）

- `zink_screen.c:3421-3426`：默认 `screen->threaded = nr_cpus>1 && GALLIUM_THREAD!=0`，`threaded_submit = threaded`（只有 `ZINK_DEBUG=flushsync` 会强制同步，实测更慢）。
- `zink_screen.c:3573` 提交队列 `util_queue_init(..., "zfq", 8, 1, ...)` 单线程 → 提交串行化，但 mc 每帧通常只有 1 个大 batch，队列不是瓶颈。
- 可量化诊断：`GALLIUM_THREAD=0`（关提交线程）跑一轮 bench，差值=提交线程的净收益；若差值很小说明这块已饱和。**低成本 BENCH-REQUEST**。
- 增大 batch：受限于 renderpass 拆分（每次 FBO/纹理屏障会 end/start batch），不是靠调参能改善。

## 6. P5：编译选项（/O3、LTO、/arch:AVX2）

- 现状：b_vscrt=mt、buildtype=release、b_ndebug=true（assert off），MSVC 默认 /O2（含 /Ob2、/Oi、/Ot）。
- 可试：`/O3`（等价提升 loop 优化）、`/GL`+`/LTCG`（LTO，链接时间明显变长）、`/arch:AVX2`（zink 有 CPU 侧向量化点，但 Mesa 对 x86 用运行时 CPU 检测，硬 AVX2 可能生成更多向量代码）。
- 诚实预期 **+0~2%** [未验证]；一次全量重编 ~40min + LTO 可能再 +10-20min，性价比低，放最后。
- 另一个更便宜的对比：原实例 DLL 是 17.45MB，我们 MSVC 构建 15.99MB（assert off + 无 EGL/GLES）。体积差主要来自 EGL/GLES、tests、debug info，不是性能因子。

## 7. P6：WGL 呈现层（几乎无油水，记录备查）

- `wgl_swap_interval`：`stw_device.c:132` 从 driconf 读，`stw_framebuffer.c:708-731 wait_swap_interval()` 是**软件 vsync 睡眠**（interval!=0 才生效）。MC 关垂直同步 → interval=0 → 不睡眠，无影响。
- zink 的 present mode：`zink_kopper.c:34-59`，interval==0 且支持 IMMEDIATE → `VK_PRESENT_MODE_IMMEDIATE_KHR`（已是低延迟最优）；不支持才退 MAILBOX/FIFO。
- `DrvSwapBuffers` → `stw_framebuffer_swap_locked`：每帧有 mutex + `stw_st_flush` + present 调用，属于必要开销；`stw_framebuffer_update` 的 GDI 路径在双缓冲下不走 GDI present（zink 走 flush_frontbuffer），无油水。

## 8. BENCH-REQUEST（按性价比排序，全部需 Lead 窗口）

| # | 类型 | 内容 | 预期 | 成本 |
|---|---|---|---|---|
| R1 | env，零代码 | `ZINK_INLINE_UNIFORMS=1`（Complementary + 关光影各一轮） | +0~3% [未验证] | 2 轮 bench |
| R2 | env 诊断 | `GALLIUM_THREAD=0` vs 默认（量化提交线程收益） | 诊断 | 1-2 轮 |
| R3 | env 增量 | `ZINK_DEBUG=rploads`、`ZINK_DEBUG=rpstores`（在 rp 默认上叠加） | ±1% [未验证] | 2 轮 |
| R4 | 改代码+DLL | P1 glthread 补丁（stw_context/stw_wgl/stw_framebuffer） | +5~15% [未验证] | 补丁+编译 2-5min+2 轮 |
| R5 | 改代码+DLL | P2 present fence wait 优化 | +0~2% [未验证] | 1-2 天+2 轮 |
| R6 | 编译选项 | /O3+/arch:AVX2（先不开 LTO） | +0~2% [未验证] | 全量重编 ~40min |

**建议顺序**：R1→R2→R3（全是 env，1 小时内出结果）→ 若 Lead 认可风险再上 R4（唯一可能两位数收益）→ R6 最后。R5 只在不满足时做。

## 9. 已知边界条件

- 任何 patched DLL 必须保持：379 导出、ordinal base 7、仅系统 DLL 依赖（单体）、GL 4.6/Vulkan 1.4 正常、zink 三家 GPU（NVIDIA/AMD/Intel）不崩。glthread 补丁是前端层，不改驱动行为，理论上 GPU 无关；但 **Windows 上只验证过 stw 路径**，AMD RADV（本机 5800H 的 Vega iGPU）必须实测一轮。
- 本机 FG 开启时 fps 是呈现帧率，A/B 必须关 FG。
- 光影首轮编译 >100s 是 disk cache 的靶子，与本文件 fps 路径无关，两者收益互不冲突。

## 10. 附：源码行号速查

- glthread 条件：`src/mesa/main/glthread.c:211-251`；marshal 执行 `glthread.c:106-159`；flush/finish `glthread.c:356-433`。
- DRI 启用逻辑：`src/gallium/frontends/dri/dri_context.c:204-236`；zink driconf 默认 `src/gallium/drivers/zink/driinfo_zink.h:9 DRI_CONF_MESA_GLTHREAD_DRIVER(true)`。
- WGL 创建 context：`src/gallium/frontends/wgl/stw_context.c:248-260`；WGL 入口 `src/gallium/targets/libgl-gdi/stw_wgl.c:63-116`；swap `src/gallium/frontends/wgl/stw_framebuffer.c:733-766`。
- zink 提交/刷新：`zink_context.c:4045-4095 flush_batch`、`4457+ zink_flush`、`4098 zink_flush_queue`；提交队列 `zink_screen.c:3573`。
- zink present：`zink_screen.c:1897-1940 zink_flush_frontbuffer`、`zink_kopper.c:869+ present_queue`、`950-971 async present`、`1106-1107 fence wait`。
- zink descriptor：`zink_screen.c:3781-3790` auto 解析；`zink_screen.c:3737 inline_uniforms 默认`；`zink_screen.c:3238-3277 init_optimal_keys`。