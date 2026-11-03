# task-8 Mesa/zink 深度优化 — 报告（mesaopt）

> 增量落盘版本 **v1.4**（2026-10-19 03:55，电池冻结期）；Lead 更新 AC 顺序（先 cache DLL fps A/B → Z0 → Z1/glthread → per-call 打点），据此重写 §11；新增 §13.1「b_ndebug 假设验证方法」+ 工具 `_probe/ndebug_markers.py`（已实测：两 DLL 均无 assert runtime 字符串 → 预期差≈0）；03:07 构建作废→根因已定位（§3.4）→**修复版已编出并通过新 gate 21/21**（§3.3，sha256 `C768681A…3A9F`，等 Lead AC 后实测 §9）；**Z0-timing 诊断补丁已就绪但未编译**（§12）；per-call 成本评估（§13）+ WGL glthread 风险（§14）；新增 `ZINK_TC_DISABLE` 隔离补丁（977 B，已校验未 apply）与 per-call 微基准工具（§13）。
> 标记 `[待验证]` = 只有推理/静态证据，尚无本机实测数字；`[已验证]` = 有命令输出佐证。
> ⚠️ **`dist/_INVALID_0307_build/` 里的任何 DLL 都不得用于实测**（原因见 §3.4）。

## 0. 结论先行

1. **可行性结论：本机可以编译 Mesa 26.2.5 的 Windows opengl32.dll，已成功产出一个 fat 单文件 DLL `[已验证]`。** MSVC VS2026 BuildTools 18.10 + MSVC 14.51.36231 + WinSDK 10.0.26100.0 + meson 1.12.1 + ninja 1.13.2 + win_bison 3.7.4 + win_flex 2.6.4，全部**本地解包**（`_tools/`，不做系统级 install、不需要管理员）；缺的工具只有 meson/ninja/bison/flex/cmake（cmake 最终没用上）。**无需联网安装任何东西即可复现**（源码/工具/压缩库都已落盘，见 §10）。
2. **ENABLE_SHADER_CACHE 在 Windows 上可以打开，但上游是硬禁的，必须打补丁。** 上游 `meson.build:1173` 用 `.require(host_machine.system() != 'windows')` 禁掉，`src/util/disk_cache_os.c` 的 Windows 分支是 TODO stub。我已实现 Win32 磁盘缓存后端 + 通用层守卫 + `meson.build` 解禁，并用 **7 个 TU 各自 `#define ENABLE_SHADER_CACHE 1`** 的方式避免全量重编（只重编 7 个 obj + 重链）。**本构建无压缩库**，因此走 raw 存储路线（5 处 `util_compress_*` 调用已加 `HAVE_COMPRESSION` 守卫），不需要 zlib/zstd 也能工作。`[已验证: 静态审计/源码核对]`
3. **换新 Mesa 二进制的替代方案 = 0 收益（关键取证）`[已验证]`：**
   - `archive.mesa3d.org`：`mesa-26.2.5.tar.xz` = HTTP 200；`mesa-26.2.6 / mesa-26.3.0 / mesa-26.3.1` 全部 **HTTP 404** → **26.2.5 就是当前最新 release**。
   - `github.com/mmozeiko/build-mesa/releases/latest`（ghfast 代理查询）→ **tag 26.2.5**，与实例 DLL 同版本。
   - 上游任何官方/第三方 Windows 构建都不会有 disk shader cache（上游禁 Windows + stub）→ 想省启动编译时间，**只有自己打补丁这一条路**。
4. **第三方构建声明（Lead 要求，写入交付说明）**：实例原 DLL 是 `mmozeiko/build-mesa` **26.2.5 的非官方包**（`_stage/ovA/工具/mesa-zink/来源与许可.txt`：MIT、`mesa-zink-x64-26.2.5.7z` 3,108,315 B、经 ghfast.top 下载、原包 sha256 未提供）。我们产出/替换的同样是**非官方自编构建**，只能声明「与实例原 DLL ABI 形态对齐（379 导出/ordinal base 7/仅系统 DLL 依赖）+ 源码取上游 Mesa 26.2.5 官方 tarball」，**不能声明等同 Mesa 官方二进制**；行为差异需 Lead 实测比对。
5. **编译选项收益（诚实结论）`[已验证: 命令行取证]`**：现有构建已是 `/O2 /MT /DNDEBUG`（`build.ninja` zink_screen.c 编译行），**没有 /GL（LTO）**。meson 1.12.1 的 MSVC 后端其实**支持 LTO**（`/GL` 编译 + `/LTCG` 链接，见 `mesonbuild/compilers/mixins/visualstudio.py:411-428`、`mesonbuild/linkers/linkers.py:1543`），但开启 = 全部命令行变化 = **全量重编 20–40min** + MSVC LTCG 在 16MB DLL/数千 obj 上有 LNK 风险，因此本次**不做**；PGO（MSVC `b_pgo` 需要 `b_lto`）还要插桩跑游戏，小时级。**编译级优化预期稳态 fps +0~2%，不作为 fps 手段**。
6. **收益主战场是「首轮/启动 shader 编译时间 + 运行中 hitch」**（实例现状：每次启动重编 IRIS+zink SPIR-V，Complementary 首轮 >100s）。disk cache 命中后 zink 把整块 `VkPipelineCache` 作为 `pInitialData` 交给 NVIDIA 驱动，免 SPIR-V→SASS 重编；同时 st 层缓存 GLSL→NIR 元数据，Iris 光影的 GLSL 前端编译也能跳过。**fps 另有独立路径**（WGL 缺 glthread 等，见 `ZINK_FPS_PATHS.md`，预计 +5~15% `[待验证]`，需另行打补丁）。
7. **★修复版 DLL 已产出并通过离线验收 `[已验证]`**：03:07 产物（16,017,408 B）与更早基线（15,996,416 B）**都缺整个 gallium/zink 驱动层**——`wgl.c` 的 `DllMain`/`stw_init`/驱动选择从未被链接器拉入，而 `libcmt.lib` 自带默认 `DllMain` 掩盖了缺符号（根因与修复见 **§3.4**）。修复后 `cmd /c build/ninja_cache_inc.bat -j4` **18.1s**（含 meson regen）产出 `dist/opengl32.dll` = **17,421,824 B**、sha256 **C768681A…3A9F**、**379 exports / ordinal base 7 与原实例 DLL 逐项一致**、仅系统 DLL 依赖、驱动+缓存 marker 全命中，离线 gate **21/21 PASS**（§3.3/§6）。**仍待 Lead 实测（R1 冒烟/R2 冷热启）**；两个旧产物已隔离在 `dist/_INVALID_0307_build/`，**禁止实测**。
8. 交付物：`dist/`（候选 DLL + `README-cache.md` 操作手册 + 校验/替换/回滚脚本）、`patches/`（**7 个补丁**：6 个 cache/fat-link + 新增 `zink-fence-z0-timing.patch`）、`build/`（幂等构建脚本 + Z0 一键脚本 + `Z0_TIMING_BUILD.md`）、`_probe/`（离线 PE 校验器、postbuild gate、Z0 gate、WGL 冒烟 exe 源码）、`ZINK_FPS_PATHS.md`（fps 路径评估）。
9. **下一阶段主战场（基于 GLCOUNT 实测）**：稳态 2118 次 GL 调用/帧、只有 89 draws/帧、真实 sync 轮询仅 7.5 次/帧 → zink 与原生 GL 的 4.5ms/帧差距 ≈ **每调用固定成本 1.2–1.6µs**，不是 draw/present/fence 问题。Mesa 侧可能方向与预估见 **§13**（`[未验证/预估]`）；下一步先跑 Z0-timing 诊断（§12）排除 fence 方向，再按 §13 顺序做 b_ndebug 差异 A/B 与 threaded-context 开关诊断。

## 1. 目标与判据（task-8）

- 目标：①评估本机能否 MSVC/meson/ninja 编译 Mesa Windows opengl32.dll（缺什么工具/装哪里/离线方案）；②能编就开 ENABLE_SHADER_CACHE/GPL/O3 等产出可替换 DLL（附导出表/sha256/依赖校验）；③不能编就量化替代方案；④A/B BENCH-REQUEST。
- 硬约束：只写 `<项目根目录>/mesaopt/`；实例 `<MC目录>/versions/DSH-Zink` 由 Lead 独占（我不启动游戏、不改实例）；任何 DLL 由 Lead 实测后才替换；不得破坏 NVIDIA/AMD/Intel 三家可跑（Mesa zink 纯 Vulkan、本后端纯 Win32 文件 IO，无 GPU 专有分支）。

## 2. 环境与工具链（全部已就位，可复现 `[已验证]`）

| 项 | 结论 | 位置 |
|---|---|---|
| MSVC | VS BuildTools 2026 18.10.12210.168 / MSVC 14.51.36231 / WinSDK 10.0.26100.0 | `VC/Auxiliary/Build/vcvars64.bat` |
| meson | 1.12.1（PyPI wheel 本地解包） | `_tools/py/`，`python -m mesonbuild.mesonmain` |
| ninja | 1.13.2（PyPI wheel 本地解包） | `_tools/ninja/ninja.exe` |
| bison/flex | win_bison 3.7.4 / win_flex 2.6.4（winflexbison 2.5.24，sha256 与 choco 官方一致） | `_tools/bin/{bison,flex}.exe`（**必须**同级 `data/m4sugar/`） |
| Python 依赖 | mako 1.4.3、PyYAML 6.0.1、packaging 26.3 | `_tools/py/` |
| zlib | 1.3.1 自编译 /MT（**当前 raw 路线不需要它**；留给将来开压缩的构建） | `_tools/zlib/` |
| Mesa 源码 | 26.2.5 官方 tarball 68,652,756 B（archive.mesa3d.org） | `src/mesa-26.2.5/` |

**踩过的坑（都已写进脚本，幂等）**
- 链接 DLL 报 `LNK1104: 无法打开文件 ...\Temp\lnk{...}.tmp`：必须在构建 bat 里 `set TMP=%ROOT%\tmp` + `TEMP=%ROOT%\tmp`，否则 DLL 链接必失败（静态库不受影响）。
- bison 需要 exe 同目录 `data/m4sugar/m4sugar.m4`，否则 `cannot open m4sugar.m4`。
- 本机 pip/choco install 类 pwsh 命令会被自动拒绝 → 改成「写 python 脚本 + 单独一次 pwsh 跑脚本」的本地解包方式（无系统级改动）。
- cmd 里 `%{http_code}` 会被吞 → 网络探测统一用 python/urllib 脚本。

## 3. 构建产物：fat opengl32.dll

### 3.1 形态取证（为什么必须是单文件）`[已验证]`
- 实例原 DLL（17,456,128 B，sha256 `4CE35C57...F6D2`）dumpbin：**依赖只有系统 DLL**（KERNEL32/USER32/GDI32/api-ms-win-core-synch-l1-2-0/ntdll），**没有** libgallium_wgl.dll / libglapi.dll 导入；379 个导出、ordinal base 7。
- zinkgl 的 MesaAgent mod 用 `LoadLibrary` 预加载 `mesa\opengl32.dll`（实例 `zink-console.log`：`[MesaAgent] preloaded mesa opengl32.dll OK` / `size=17456128`）。Windows 的 DLL 依赖搜索**不包含被加载 DLL 所在目录** → 双 DLL 形态有加载失败风险 → 必须复刻「全部静态链入 opengl32.dll」的 fat 单体。
- 链接方案（`patches/meson-fat-wgl.patch`、`patches/meson-fat-opengl32.patch`）：
  - `src/gallium/targets/wgl/meson.build`：`libgallium_wgl` → `static_library('gallium_wgl_combined', ['wgl.c'], link_with:[libgallium,libglsl,libmesa,libwsgdi,libglapi_bridge,libglapi,libgalliumvl_stub], dependencies:[dep_ws2_32,idep_nir,idep_mesautil,driver_swrast,driver_d3d12,driver_zink,winsys_d3d12_wgl])`（**不能用 link_whole**，否则 LNK2005 大面积重复）。
  - `src/gallium/targets/libgl-gdi/meson.build`：`link_whole:[libwgl]` + `link_with:[libgallium_wgl,libgallium,libglsl,libmesa,libwsgdi,libglapi_bridge,libglapi,libgalliumvl_stub]` + 同套 dependencies + `c_args ['-D_GDI32_']`。
- 构建脚本：`build/setup_base.bat`（meson setup）+ `build/ninja_base.bat`（ninja）；构建选项 `--buildtype=release -Db_ndebug=true -Db_vscrt=mt -Dplatforms=windows -Dgallium-drivers=zink -Dvulkan-drivers= -Dopengl=true -Degl=disabled -Dgles1=disabled -Dgles2=disabled -Dllvm=disabled -Dxmlconfig=disabled -Dzstd=disabled -Dzlib=disabled -Dshader-cache=disabled -Dbuild-tests=false`。

### 3.2 基线产物（未开 shader cache）— ❌ 事后判定不可用（见 §3.4）`[已验证]`
- `build/base/src/gallium/targets/libgl-gdi/opengl32.dll` = **15,996,416 B**，sha256 `7A205F779E1ACAEC358E11690EA295D8D8EEBB11A97352BE086563D92A934D96`。
- dumpbin/PE 校验：machine 0x8664；**379 exports、379 names、ordinal base 7**；段 `.text 0x5d6258 / .rdata 0x915ad2 / .data 0x38a28 / .pdata 0x40b48 / _RDATA 0x30 / .fptable 0x100 / .reloc 0x8fe0`（与原 DLL 同族）；依赖 `GDI32.dll KERNEL32.dll USER32.dll api-ms-win-core-synch-l1-2-0.dll`。
- 已知警告：`LNK4006 vk_dispatch_table` 两份 obj（`libzink.a.p/meson-generated_..._vulkan_util_vk_dispatch_table.c.obj` 与 `libvulkan_util.a.p/meson-generated_.._vk_dispatch_table.c.obj`），链接器保留第一份 → **低风险但必须实测 zink 正常**（BENCH-REQUEST R1）。
- ❌ **事后复核（03:2x）**：该 DLL 内 `GALLIUM_DRIVER=0 / LIBGL_ALWAYS_SOFTWARE=0 / vkCreatePipelineCache=0`，`zink` 仅 6 个命中且经 hexdump 全是 NIR intrinsic 名 → **完全没有 gallium/zink 驱动层**，不可用；只能当「fat 链接形态 + 导出表」的形态样本。教训：体积/导出/依赖三项校验**不足以**证明 DLL 可用（§3.4）。

### 3.3 shader-cache 版：03:07 增量编译已跑完，但产物作废 `[已验证]`
- 方案：**不 reconfigure**（`-Dshader-cache=disabled` 的构建目录不动，避免全量重编），7 个 TU 顶部各加 `#define ENABLE_SHADER_CACHE 1`，只重编 7 obj + 重链。脚本 `build/ninja_cache_inc.bat`（`ninja %*`，日志 `build/ninja_cache_inc.log`）。
- 实际耗时：03:07:04.40 → **03:07:08.75，`ninja -j4` exit=0，14 步**（[1/16] git_sha1 → disk_cache.c → disk_cache_os.c → state_tracker_st_shader_cache.c → shader_cache.cpp → glsl_parser_extras.cpp → libmesa_util.a → libglsl.a → st_glsl_to_nir.cpp → zink_screen.c → libzink.a → libmesa.a → libgallium_wgl_combined.a → opengl32.dll），仅 LNK4006 警告 → **增量编译成本 = 5 秒级**（不是分钟级），后续每次 Mesa 侧小补丁都很便宜。
- 产物：`build/base/src/gallium/targets/libgl-gdi/opengl32.dll` = **16,017,408 B**，sha256 `89E3886638266F30B3DE453600C3DC04C18FF8E71F755BAF725764D942650B4B`；379 exports / ordinal base 7 / 7 段 / 仅系统 DLL 依赖。
- ❌ **ABI 形态全对，但 DLL 缺整个 gallium 驱动层 → 作废**（原因/修复见 §3.4）。已整体移入 `dist/_INVALID_0307_build/`（含当时生成的 MANIFEST/verify/dumpbin），并留 `INVALID.md` 说明。

#### ✅ 修复版（Lead 批准的电池段唯一一次编译，03:20:52–03:21:10）`[已验证]`
- 构建：`ninja -j4` exit=0，**18.1 s**（meson regen + 只编 `wgl.c` + 链接 DLL；日志 `build/ninja_cache_inc.log`），postbuild gate 7.2 s。
- 产物：`dist/opengl32.dll` + `dist/opengl32-cache-26.2.5.dll` = **17,421,824 B**（原实例 17,456,128 B，差 -34,304 B），sha256 **`C768681A31293E7F600742A70FF2B09C1D039A5DC29364504D6AAE9D15CC3A9F`**（两副本一致）。
- 验收（新 gate **21/21 PASS**）：exports 379 / names 379 / ordinal base 7 / DrvCopyContext + wglUseFontOutlinesW；`GALLIUM_DRIVER=1, LIBGL_ALWAYS_SOFTWARE=2, vkCreatePipelineCache=2, zink=39`（原 DLL 38）、4 个 cache marker 各 1；dumpbin 导入仅 `api-ms-win-core-synch-l1-2-0/KERNEL32/USER32/GDI32`（无 libgallium_wgl/libglapi/vulkan-1）。
- **导出表逐项比对（新脚本 `_probe/cmp_exports.py`）**：原实例 DLL 379 个 ordinal（7..385）↔ 新 DLL 379 个 ordinal（7..385），**ordinal→name 映射完全一致（EXPORT TABLE IDENTICAL）** → 这才是完整的 ABI 证据（之前只比了导数/首尾符号/ordinal base）。证据落盘 `dist/ABI_EVIDENCE.txt`。
- 结论：**这是第一个「形态 + 驱动层 + 缓存层 + 完整导出表」四项都对的自编候选**，等 Lead 冒烟/冷热启实测（R1/R2，见 §9）；实测前不替换实例。

### 3.4 ★03:07 事故：fat DLL 从未链入 gallium 驱动层（根因 + 修复 + 教训）`[已验证]`
- **现象**：新 DLL 里 `GALLIUM_DRIVER / LIBGL_ALWAYS_SOFTWARE / vkCreatePipelineCache / 4 个 disk-cache marker / zcfq / ZINK_INLINE_UNIFORMS / ZINK_DESCRIPTORS` **全部 0 命中**；而实例原 DLL（17,456,128 B）有 `GALLIUM_DRIVER=1, LIBGL_ALWAYS_SOFTWARE=2, vkCreatePipelineCache=2, zcfq=1, ZINK_INLINE_UNIFORMS=2, ZINK_DESCRIPTORS=1, blake3=3, zink=38`。新 DLL 的 6 个 `zink` 经 hexdump 确认全来自 NIR intrinsic 名（`is_sparse_resident_zink` / `load_push_constant_zink`），不是驱动代码。段对比：新 `.text 0x5d7000 (6.1MB) / .rdata 0x916000` vs 原 `.text 0x70f000 (7.4MB) / .rdata 0x938000` → 少的 ~1.3MB 正好是 zink+Vulkan 代码。
- **判据（已写进 gate）**：`zink` 计数 < 20 或 `GALLIUM_DRIVER==0` ⇒ 驱动层没进 DLL，直接判废，不必再看体积/导出。
- **根因**：`opengl32.dll` 的 def 只导出 gl*/wgl*/Drv*，链接器只拉这些符号的实现（`stw_wgl.c` + `/WHOLEARCHIVE:libwgl.a` + glapi）；而**唯一**定义 `DllMain`（`stw_init(&stw_winsys)` + `stw_init_thread()`）和 `GALLIUM_DRIVER` 驱动选择（zink 分支 `zink_create_screen`，`src/gallium/targets/wgl/wgl.c:97-103`，DllMain 在 `wgl.c:264`）的 `wgl.c` **没有任何符号被引用 → 从未被拉入**。链接器不报错的原因：**MSVC `libcmt.lib` 自带一个默认 `DllMain` 符号**（`dumpbin /symbols libcmt.lib` 见 `External | DllMain`），所以 `_DllMainCRTStartup` 有定义、**不会 LNK2019** → 静默产出「能加载但 wglCreateContext 必失败」的 DLL。`/OPT:REF` 与 17 个归档的顺序都与此无关。构建图里确认：`libgallium_wgl_combined.a`（829 members，61,701,678 B）**确实含 wgl.c.obj**（GALLIUM_DRIVER=2/DllMain=3），只是链接器按需没拉。
- **修复（已落源码树，等 AC 窗口重编）**：把 `wgl.c` 提成 `wgl_common_sources = files('wgl.c')`，**同时**编进 `libgallium_wgl_combined` 和 `opengl32.dll`（`src/gallium/targets/{wgl,libgl-gdi}/meson.build`，均附 `[dsh-mesaopt]` 注释）；wgl.c 里对 `zink_create_screen` 的引用会把整条 libzink/vulkan-util 链拉进 DLL。预期 ~17.4MB、仍 379 exports、GALLIUM_DRIVER/vkCreatePipelineCache/cache marker 全 ≥1。重编量＝regen + wgl.c + 重链 ≈ **60–90s（-j4）**。
- **教训**：①**体积 + 导出数 + 依赖列表三项校验不足以证明 DLL 可用**（漏了一个整层驱动）；②MSVC 静态 CRTL 的默认 `DllMain` 会掩盖「自定义 DllMain 没链进来」这种致命错误；③以后任何 Windows DLL 交付都必须带「功能 marker 计数」验收。
- gate 已升级并双向自测：`_probe/verify_cache_dll.py` 的 MARKS 增加 `GALLIUM_DRIVER`/`LIBGL_ALWAYS_SOFTWARE`/`vkCreatePipelineCache`；`_probe/postbuild_check.py` 体积窗口收窄到 **16,500,000–18,600,000**、新增 3 个 driver marker 必须 ≥1、`zink` 必须 ≥20。实测：实例原 DLL → 三个 driver marker 全 ≥1（zink=38）；03:07 坏 DLL → 全 0、zink=6，**按新 gate 必 FAIL**（`_probe/gatecheck_orig.json` / `gatecheck_broken.json` 留档）。

## 4. ENABLE_SHADER_CACHE 移植（源码级）

### 4.1 为什么值钱（源码取证）
- **zink 层**：`src/gallium/drivers/zink/zink_screen.c:361` `screen->disk_cache = disk_cache_create("zink", cache_id, 0);`；380-413 `cache_put_job` 用 `VkGetPipelineCacheData` 取**整块 VkPipelineCache 二进制**，408 `disk_cache_compute_key(screen->disk_cache, pg->blake3, sizeof(pg->blake3), key)`，409 `disk_cache_put_nocopy`；427-450 `cache_get_job` 同 key `disk_cache_get`(442) 后作为 `pcci.pInitialData`(内) 建 `VkPipelineCache` → **命中即免 NVIDIA 驱动的 SPIR-V→SASS 重编**（首轮 >100s 的主因）。
- **GLSL 前端层**：zink_screen.c:3289-3294 `zink_get_disk_shader_cache` → `st_context.c:795 ctx->Cache = ...` → `src/mesa/state_tracker/st_shader_cache.c` 与 `src/compiler/glsl/shader_cache.cpp` 缓存 GLSL→NIR program metadata；`glsl_parser_extras.cpp:2328-2362 can_skip_compile` 命中时直接 `COMPILE_SKIPPED` 跳过整段 GLSL 编译。
- 本机现状：实例 DLL 未编 cache（disk_cache 字符串 0 命中，见 `zinkperf/MESA_MECHANISMS.md`；我复核 `_stage/ovA/工具/mesa-zink` 同样 0 命中）→ 每次启动重编。

### 4.2 上游封锁 + 我们的移植
- 封锁点：`meson.build:1173-1175` `.require(host_machine.system() != 'windows', error_message : 'Shader Cache does not currently work on Windows')`；`src/util/disk_cache_os.c` Windows 分支原为 `/* TODO: implement disk cache support on windows */`；`meson.build:1812-1817` 无压缩库时 `error('Shader Cache requires compression')`。
- 已改（全部落盘，diff 见 `patches/`）：
  1. `src/util/disk_cache_os.h` Windows 版 `struct disk_cache`（无 foz_db/cache_db；内存索引 `stored_keys`/`size`/`size_is_valid`）、`struct cache_entry_file_data{crc32, uncompressed_size}`、`struct disk_cache_put_job`、全部 OS 函数声明（`patches/disk-cache-win32-os-header.patch`）。
  2. `src/util/disk_cache_os.c` 完整 Win32 后端（`patches/disk-cache-win32-os-impl.patch`）：目录 `<base>/<driver_id>/<key[0:2]>/<key-hex>.bin`；`base` = `MESA_SHADER_CACHE_DIR`/`MESA_GLSL_CACHE_DIR`，否则 `%LOCALAPPDATA%\mesa_shader_cache`（fallback `%TEMP%`）；entry = 12B 头 `{crc32, uncompressed_size}` + payload（本构建 raw 存储）；写入 `CreateFileW CREATE_ALWAYS`，读取 `OPEN_EXISTING + GetFileSizeEx + 分块 ReadFile + crc32 校验`；内存索引 + `p_atomic_uint64_t size`；LRU = 目录递归扫描最旧 ftLastWriteTime 删除；**所有失败路径 = cache miss**；FOZ/DB 后端在 Windows 为 stub。
  3. `src/util/disk_cache.c` 全部 POSIX-only 分支加 `DETECT_OS_WINDOWS` 守卫（POSIX 头、FOZ/DB 的 create/get/put/remove/destroy、DATABASE size limit、SINGLE_FILE），Windows 强制 MULTI_FILE（`patches/disk-cache-win32-generic.patch`）。
  4. `meson.build` 解除 Windows 限制（`with_shader_cache = get_option('shader-cache').allowed()`，`patches/meson-allow-shader-cache-windows.patch`）。
  5. 5 处 `util_compress_*` 调用加 `HAVE_COMPRESSION` 守卫（`disk_cache.c` blob_put/get_compressed；`disk_cache_os.c` write/load）→ **无压缩库也能编译**，raw 存储。
- **per-TU define 的 7 个 TU（为什么是这 7 个）**：
  | TU | 作用 |
  |---|---|
  | `src/util/disk_cache.c` | 通用层全部实现（文件体在 `#ifdef ENABLE_SHADER_CACHE` 内） |
  | `src/util/disk_cache_os.c` | Windows 后端文件体（同上） |
  | `src/gallium/drivers/zink/zink_screen.c` | cache create(361)/teardown(1622-1629)，zink 侧读写 |
  | `src/mesa/state_tracker/st_shader_cache.c` | 用 `ctx->Cache` 缓存 GLSL→NIR program |
  | `src/mesa/state_tracker/st_glsl_to_nir.cpp` | `st_load_nir_from_disk_cache`/`st_store_nir_in_disk_cache` 调用点 |
  | `src/compiler/glsl/shader_cache.cpp` | metadata 的 put/get/compute_key 实体 |
  | `src/compiler/glsl/glsl_parser_extras.cpp` | `can_skip_compile` 的 `has_key`/`put_key`（无 ifdef，不定义就永远 miss 且 key 全 0 撞车） |
- ABI 风险核对（`[已验证]`）：zink 目录只有 `zink_screen.c` 有 ifdef；`zink_types.h:48/1420-1422` 的 `struct disk_cache *` 只是 opaque 指针、`struct util_queue` 无 ifdef → 混合 TU **无 struct 布局风险**；唯一有布局 ifdef 的是 v3d（本构建不含）。
- 静态审计（`[已验证]`）：`disk_cache_os.c` 2131 行括号平衡 0；头文件 19 个 Windows 分支声明全部有定义；`disk_cache.c` 内无任何 POSIX 残留引用；`disk_cache_get_function_identifier` 在 `disk_cache_os.c` 的 `#if DETECT_OS_WINDOWS` 内、**不在** `ENABLE_SHADER_CACHE` 内（不会 LNK2019）。

### 4.3 运行期行为
- 默认就开启（`disk_cache_enabled` 只在 `MESA_SHADER_CACHE_DISABLE=true`/`MODE=disabled` 时关；本构建不含 `SHADER_CACHE_DISABLE_BY_DEFAULT`，因为该宏只在 meson 的 `shader-cache` 分支里、且 `shader-cache-default` 默认 true）。
- 统计：`MESA_SHADER_CACHE_SHOW_STATS=true` 时销毁打印 `disk shader cache:  hits = N, misses = M`（`disk_cache.c:367-371`，mesa_logi）。缓存目录/环境变量/打包说明见 `dist/README-cache.md`。

## 5. 替代方案量化（task-8 ③）

| 方案 | 能省什么 | 量化/证据 | 结论 |
|---|---|---|---|
| **A. 自编 DLL + disk cache（本方案）** | 每次启动重编 IRIS+zink SPIR-V 的时间（现状 >100s）+ 运行中 pipeline 编译 hitch | 源码路径确定（zink_screen.c:380-450 / glsl_parser_extras.cpp:2328）；实测待 Lead：预期热启省 60–100s `[待验证]`；稳态 fps 0~+2% | **主推** |
| B. 换 Mesa 官方/第三方新二进制 | 上游 bugfix 带来的稳态 fps 微调 | `archive.mesa3d.org` 26.2.5=200、26.2.6/26.3.0/26.3.1=**404**；mmozeiko latest tag=**26.2.5**；且上游 Windows 构建**没有** disk cache | **0 收益，放弃** |
| C. 预置 SPIR-V/VkPipelineCache 缓存包 | 第一次启动就是热启动 | 在本方案上再进一步：热启一次后把缓存目录打包进 `dist/cache-preset/`；限制：**同 GPU+同驱动**（cache_id 含 pipelineCacheUUID） | **值得做，依赖本方案先上线 `[待验证]`** |
| D. zinkgl 预热/预加载 | 减少首帧前的加载时间 | 实例已有 MesaAgent 预加载；Mesa 无「预热 API」；预热=重复启动热驱动缓存，无额外空间 | **0 收益** |
| E. WGL glthread（GL 命令多线程化） | 每帧 GL 调用 CPU 开销（zink 每帧多 ~4.5ms，CPU 受限） | `_mesa_glthread_init` 全源码唯一调用点 `src/gallium/frontends/dri/dri_context.c:236`；`src/gallium/frontends/wgl/` 0 命中 → **WGL 前端没接 glthread**，`mesa_glthread=true` 是 no-op；补丁设计见 `ZINK_FPS_PATHS.md` P1，预期 +5~15% `[待验证]`，工作量 80-120 行 + 2 轮 bench | **fps 方向首选，需 Lead 排优先级** |
| F. LTO/PGO/AVX2 编译选项 | 稳态 fps | /O2 /MT /DNDEBUG 已开；LTO 可用但全量重编 20-40min；PGO 需插桩跑游戏 | **+0~2%，暂不做** |

## 6. 离线验证与交付 gate（不启动游戏）

- `_probe/verify_cache_dll.py`：纯 Python PE 解析（machine/段表/SizeOfImage/export 目录 NumberOfFunctions+OrdinalBase+符号名）+ sha256 + **marker 字符串计数** + 依赖名粗扫。marker 现分两组：①缓存层 `mesa_shader_cache` / `MESA_SHADER_CACHE_DIR` / `MESA_GLSL_CACHE_DIR` / `MESA_DISK_CACHE_MULTI_FILE` / `disk shader cache:  hits`；②驱动层 `GALLIUM_DRIVER` / `LIBGL_ALWAYS_SOFTWARE` / `vkCreatePipelineCache` + `zink` 计数。**双向实测**：实例原 DLL = 379 exports / ordinal base 7 / 驱动 marker 1·2·2 / `zink`=38；03:07 坏 DLL = 驱动 marker 全 0 / `zink`=6 → 被正确判废（`_probe/gatecheck_orig.json`、`gatecheck_broken.json`）。
- `_probe/postbuild_check.py` + `build/postbuild_cache.bat`：20 项 gate（**体积窗口 16.5–18.6MB** / 机器 / 379 导出×2 / ordinal 7 / 关键符号 / 4 个 cache marker ≥1 / **3 个 driver marker ≥1** / **`zink`≥20** / 必需系统依赖 / 禁止 gallium·glapi·EGL·GLES·vulkan 依赖 / dumpbin 权威依赖 / dist 副本 hash 一致），自动写 `dist/SHA256SUMS.txt` + `dist/MANIFEST.txt`。**升级原因**：v1 的 17 项自测时，拿基线 DLL 跑「13/17 通过、只 FAIL 4 个 cache marker」就认为 gate 正确 —— 但基线 DLL 本身也缺驱动层，**旧 gate 会放行不可用 DLL**（这是本次漏检根因）。新 gate 已用实例原 DLL（驱动项全 PASS、cache 项 FAIL，符合预期）与 03:07 坏 DLL（驱动项全 FAIL）双向验证。
- `_probe/gl_smoke.c` + `build_glsmoke.bat`：**不启动游戏**的独立 WGL 冒烟 exe（LoadLibraryW 全路径 → 隐藏窗口 → ChoosePixelFormat/SetPixelFormat/wglCreateContext/wglMakeCurrent 全走 GetProcAddress → 打印 GL_VERSION/RENDERER/VENDOR，统计缓存目录条目；exit code 分级）。**尚未编译/未运行**（Lead 要求等窗口，避免抢 CPU）。
- `dist/README-cache.md`：环境变量表、冷/热启 A/B 步骤、预置缓存打包方法、三显卡兼容说明、回滚步骤。

## 7. 替换 / 回滚（Lead 专用）

- 实例原 DLL（只读复核）：`opengl32.dll` 17,456,128 B，sha256 `4CE35C570C0BFE81825099CE03B95AD1E4173155B8584EDE41D646E21542F6D2`（副本 `_probe/orig/opengl32.dll` 同 hash）。**注意**：实例 `mesa/` 目录当前是否存在由 Lead 控制（曾改名 `mesa.off/`），替换前先确认目录恢复。
- `dist/replace.bat <候选DLL>`：①检测 javaw.exe 未运行；②备份现有 DLL 到 `dist/backup/opengl32.dll.<时间戳>.bak`；③候选体积 sanity(<10MB 拒绝)；④copy；⑤打印 size+sha256。
- `dist/rollback.bat`：取最新 `.bak` 覆盖回去；同样要求 javaw 未运行。
- `dist/check_dll.bat <DLL>`：独立离线校验入口（不需要编译环境）。

## 8. 风险与遗留

| 风险 | 影响 | 处置 |
|---|---|---|
| **fat DLL 漏链某层代码（已发生一次）** | 产物「能加载但无驱动」，且旧 gate 全 PASS | 根因见 §3.4；现已修 meson + gate 加驱动 marker/体积窗口；**教训：Windows DLL 验收必须有功能 marker 计数，不能只看体积/导出/依赖** |
| LNK4006 `vk_dispatch_table` 重复 | zink Vulkan dispatch 可能取错表 | BENCH-REQUEST R1 先实测启动/关光影；异常则回退 link_whole 方案重编 |
| Win32 disk cache 后端是新代码（未跑过游戏） | 可能不写/写坏缓存 | 所有失败路径=cache miss；实测时看缓存目录文件数与热启时间；`MESA_SHADER_CACHE_DIR` 重定向到 mesaopt 便于检查 |
| per-TU define 造成「部分 TU 开了 cache、部分走 stub」 | 若某 TU 调用被内联 stub 会静默 miss | 已审计：zink/st 只有 `struct disk_cache *` opaque 指针，无布局 ifdef；7 个必要 TU 全覆盖（§4.2） |
| 未链接压缩库 → 缓存条目为 raw | 缓存目录比 deflate 大 2-5× | 1GB 上限内可接受；要压缩需重建带 zlib 的构建（`_tools/zlib` 已就绪） |
| 第三方构建声明 | 不能声明等同 Mesa 官方 | 交付文档明确：源码=上游 26.2.5 官方 tarball + 我们的补丁；非官方包，需实测比对 |
| DLL 无签名 | 杀软可能误报 | 与原实例 DLL 相同情况；保留备份/回滚 |
| 编译优化 fps 收益有限 | 不达「超过原生 Vulkan」 | 已诚实告知；fps 走 zink 源码路径（glthread/present fence，见 `ZINK_FPS_PATHS.md`） |
| 三显卡兼容 | AMD/Intel 上行为未知 | 代码无 GPU 专有分支；仍需 Lead 在可用机器上冒烟（本机只有 NVIDIA） |

## 9. BENCH-REQUEST（最终版，待 Lead 排窗口）

> ⚠️ **候选 DLL 已就绪**：`dist/opengl32.dll`（17,421,824 B，sha256 `C768681A…3A9F`，gate 21/21 PASS，§3.3）。**只允许 Lead 实测/替换**；替换前用 `dist/check_dll.bat` 复核，失败走 `dist/rollback.bat`。**`dist/_INVALID_0307_build/` 里的产物禁止实测。**

前置：游戏/PCL 关闭；实例 `mesa/` 目录就位；canonical config（FG=OFF、SR 由 `srset.mjs` 控制）；每轮记录 `zinkfps` 样本 + 日志时间戳。

- **R1 冒烟（2–3min）**：替换为候选 DLL → 关光影启动一次 → 确认：能进世界、F3 显示 Zink/Mesa 26.2.5、GL 4.6、无 `ERROR`/崩溃；日志无 `vk_dispatch_table` 相关异常。失败→`rollback.bat`。
- **R2 cold/warm 启动（核心，需要 2 次启动）**：
  - 环境 `MESA_SHADER_CACHE_DIR=<项目根目录>\mesaopt\cache` + `MESA_SHADER_CACHE_SHOW_STATS=true`；先清空该目录。
  - cold：进世界（Complementary r5.9.3，与基线同配置），记录「进世界→光影/资源编译完成、首次稳定 60s」耗时、zinkfps 均值、退出后缓存目录 `文件数/总字节`、日志 hits/misses。
  - warm：同配置再启动，记录同样指标。预期：hits ≫ misses，编译等待大幅下降（>100s → 显著更小 `[待验证]`），稳态 fps 变化在噪声内。
  - 对照：同一 DLL 加 `MESA_SHADER_CACHE_DISABLE=true` 再跑一次 warm（应退回 cold 行为）→ 证明收益来自 cache 而非 DLL 本身。
- **R3 三显卡/回归**：若可能，在 AMD/Intel 机器各启动一次（不要求 fps 数字，只要求不崩、GL 正常）。
- **R4（可选，fps 方向）**：等 glthread 补丁编好后单独排 A/B（见 `ZINK_FPS_PATHS.md` R4）。

## 10. 交付物清单（<项目根目录>/mesaopt/）

- `dist/`：**当前候选 = `opengl32.dll` + `opengl32-cache-26.2.5.dll`（17,421,824 B，sha256 `C768681A…3A9F`，两副本一致）**；另有 `SHA256SUMS.txt`、`MANIFEST.txt`（21/21 PASS）、`verify_cache.txt/.json`、`dumpbin_exports.txt`/`dumpbin_dependents.txt`、`ABI_EVIDENCE.txt`（导出表逐项一致证据）、`README-cache.md`、`check_dll.ps1|bat`、`replace.bat`、`rollback.bat`、`_INVALID_0307_build/`（03:07 作废产物 + `INVALID.md`，**禁止实测**）。
- `patches/`（**8 个已生成 + 1 个待补**）：`disk-cache-win32-os-header.patch`、`disk-cache-win32-os-impl.patch`、`disk-cache-win32-generic.patch`、`meson-allow-shader-cache-windows.patch`、`meson-fat-wgl.patch`（2037 B，已含 wgl.c 进 DLL 的 03:07 事故修复 `wgl_common_sources`）、`meson-fat-opengl32.patch`（1892 B，同上）、`zink-fence-z0-timing.patch`（9,377 B/206 行，Z0-timing，VALIDATION PASS）、`zink-tc-disable.patch`（**977 B**，`ZINK_TC_DISABLE` env 门控强制非 threaded pipe_context，用于隔离 `u_threaded_context` 包装成本；`_probe/gen_tc_patch.ps1` 生成，shadow-tree `git apply --check/-p1`=0 + `APPLIED_OK=True`，**live 源码树未 apply**）。
  - ⏭ 待补：`disk-cache-per-tu-define.patch`（7 个 TU 的 `#define ENABLE_SHADER_CACHE 1`，需从官方 tarball 取原始文件做 diff，xz 解压稍耗 CPU，留到 AC 期做）。
  - 注：两个 meson patch 的 diff 路径带 `.orig` 后缀（用 tree 内 `.orig` 备份生成），作为内容记录可读；`git apply` 需先按 §3.1/§3.4 的描述改两个 meson.build。
- `build/`：`setup_base.bat`、`ninja_base.bat`、`ninja_cache_inc.bat`、`postbuild_cache.bat`、`setup_cache.bat`/`ninja_cache.bat`（全量构建用，本次不用）
- `_probe/`：`verify_cache_dll.py`、`postbuild_check.py`、`gl_smoke.c`（含 `--bench=N` per-call 微基准）+ `build_glsmoke.bat` + `run_percall_bench.bat` + `PERCALL_BENCH.md`、`z0_postbuild.py`、`gen_z0_patch.py`、`gen_tc_patch.ps1`、`fetch_*.py`、`orig/opengl32.dll`、`selftest/`、`z0base/`（Z0 改动前基线）、`tcbase/`+`tcbase_new/`+`tccheck/`（TC patch shadow 校验）
- `_tools/`：meson/ninja/bison/flex/mako/PyYAML/packaging/zlib（离线可复现）
- `src/mesa-26.2.5/`：Mesa 26.2.5 源码树（含全部补丁；两个 meson.build 的 `.orig` 备份）
- `ZINK_FPS_PATHS.md`：zink/GL 侧 fps 路径评估（P1 glthread、P2 present fence、P3 descriptor/renderpass、P4 提交、P5 编译选项、P6 WGL 呈现）

## 11. 下一步（Lead m01378 最新 AC 顺序；与 STATUS §11 同步）

> Lead AC 顺序（2026-10-19 03:5x 更新）：①候选 cache DLL vs 实例原 DLL 稳态 fps A/B（验证 b_ndebug=true，可能白捡 0.5–3%）②Z0-timing build + 一轮 60–120s 采集 ③视 Z0 结果决定 Z1/glthread ④per-call 方向 L1/L2/L3 打点（zinkperf 的 `MESA_ENTRY_COST_PROFILE.md` 已交付，含函数清单/行号）。下面只列 mesaopt 的对应动作。

1. **①cache DLL 稳态 fps A/B（最高优先）**：Lead 用 bench3 的 mesa 替换逻辑装 `dist/opengl32.dll`，先 **R1 冒烟**（确认 zink 正常启动、GL 4.6、无黑屏），再跑候选 vs 原 DLL 的稳态 fps 对照（同配置、关 FG、1080p、无光影；至少 2×2 交替）。cache 本身不改稳态 fps，这一 A/B 的唯一预期差异就是 b_ndebug/代码布局 —— 已用字符串取证预测差 ≈0（§13.1），所以它是「廉价终验」而不是主收益来源。若 A/B 出现 ≥1% 稳定差异，再考虑把候选 DLL 作为默认分发。
2. **②Z0-timing 诊断 build**：一键 `cmd /c <项目根目录>\mesaopt\build\build_z0.bat -j4`（60–150 s，源码/patch/脚本/gate 全就绪，见 §12 与 `build/Z0_TIMING_BUILD.md`）→ Lead 带 `ZINK_FENCE_STATS=1` 采集 60–120 s（无光影/1080p/SR 关/FG 关/前台）→ 按 T5 判据读 `dist/z0` 统计行决定 Z1/Z2/Z3a。注意此 build = **cache+Z0 合并版**（cache 功能不受影响）。
3. **③视 Z0 结果**：若数据显示 deferred flush / vkWaitSemaphores 占比可忽略（当前 7.5 次轮询/帧的实测倾向如此）→ 主战场转向 ④ per-call，并把 Z1/Z3a/glthread 降级为「可选实验」。glthread 风险清单见 §14，需 Lead 明确排期。
4. **④per-call L1/L2/L3 打点**：zinkperf 的 `MESA_ENTRY_COST_PROFILE.md`（函数清单+行号）与我方 `_probe/gl_smoke.c --bench=N` offscreen 微基准**互补**：
   - gl_smoke = 纯 Mesa 侧 **ns/call 绝对值**（10 个测点 + `mixed_2118` 合成帧；实例原 DLL / 候选 DLL / 系统 opengl32 三对照；`--driver=zink`）。Lead 已确认：**这是目前唯一能测「纯 Mesa 侧 ns/call」的方法**（shaders 的 in-game agent 因 classloader 冲突暂时取不到数）。成本：编 exe 2–5 s + 跑 10–20 s，须 Lead 批准且不与 bench 并发（会创建 GL context）。
   - 打点版 DLL = 在 gl_smoke 指出热点层后再改，避免盲改。
5. **⑤R2 磁盘缓存冷/热启**（可与 ①同一次 bench 顺带）：量化「>100s 首轮编译」能省多少 + 验证缓存目录真的写入；好则做预置缓存包（同 GPU+同驱动才有效），失败则开 `MESA_SHADER_CACHE_SHOW_STATS` 排查（所有失败= cache miss，最坏零收益）。
6. 若 Lead 要「超过原生 Vulkan」的进一步方案：证据指向 Java 侧渲染路径（2118 calls/帧、89 draws、~950 状态调用、~90 状态查询），而非继续压 Mesa 编译选项；见 `ZINK_FPS_PATHS.md` §0 与 §13。

## 12. Z0-timing 诊断补丁（已就绪，未编译）`[已验证: 源码/补丁/脚本] / [运行时未验证]`

**目的**：用一次增量编译回答「fence 轮询路径到底花多少时间」，决定 Z1（poll 不强制 flush）/Z2（每 N 次兜底 flush）/Z3a（限频 `vkWaitSemaphores`）是否值得做。背景：GLCOUNT 实测稳态 sync 轮询只有 **7.50 次/帧**（AcceleratedRendering 5.50 + Sodium 2.00），缺口 2.55–3.40 ms ÷ 2119 次调用 = **1.2–1.6 µs/次** → 主假设已从「fence 自旋」转向「每次 GL 调用固定成本」。

**改动（4 个文件，全部 env 门控、默认=原行为；03:55 已把 TC 隔离补丁并入同一份 DLL）**：

| 文件 | 改动 |
|---|---|
| `src/gallium/drivers/zink/zink_types.h` | `struct zink_tc_fence` 加 `unsigned poll_count`；`struct zink_screen` 加 5 个 tuning 字段 + `fence_stat`{finish_calls, polls_zero, deferred_flush, deferred_skipped, deferred_ns, tc_flush, tc_flush_ns, fence_wait, fence_wait_ns, wait_sem_calls, wait_sem_ns, wait_sem_skipped, stats_last_ns} |
| `src/gallium/drivers/zink/zink_screen.c` | `#include "util/os_time.h"`；`abort_on_hang` 后解析 4 个 env；`zink_screen_timeline_wait` 加 Z3a 限频 + `VKSCR(WaitSemaphores)` 计时（只计真实调用） |
| `src/gallium/drivers/zink/zink_fence.c` | `<stdio.h>`+`util/os_time.h`；`zink_fence_finish` 计数 + 每 4096 次打印一行 + Z1/Z2 deferred 分支 + flush 计时 + `fence_wait` 计时；`tc_fence_finish` 的 `threaded_context_flush` 计时 |
| `src/gallium/drivers/zink/zink_context.c` | **（03:55 追加）** `zink_context_create` 的 `PIPE_CONTEXT_PREFER_THREADED` 判断加 `|| debug_get_bool_option("ZINK_TC_DISABLE", false)` → `ZINK_TC_DISABLE=1` 强制非 threaded pipe_context，用于隔离 `u_threaded_context` 包装成本（`GALLIUM_THREAD=0` 会同时关 threaded_submit，做不到）；`patches/zink-tc-disable.patch` 977 B，已 apply 到 live 树（shadow-tree 校验 APPLIED_OK=True）。 |

**env**（全部 `debug_get_*`，`zinkgl.env` 传不进去，必须由 bench env 注入）：`ZINK_FENCE_STATS`（0=关）、`ZINK_POLL_NO_FLUSH`、`ZINK_POLL_FLUSH_EVERY`、`ZINK_POLL_SEM_EVERY`、`ZINK_TC_DISABLE`（0=原行为，=1 强制非 threaded context）。

**统计行**（stderr，单行）：`ZINK_FENCE_STATS dt_ms=… finish_calls=… polls_zero=… deferred_flush=… deferred_skipped=… deferred_avg_us=… tc_flush=… tc_flush_avg_us=… fence_wait=… fence_wait_avg_us=… wait_sem_calls=… wait_sem_skipped=… wait_sem_avg_us=…`。每帧速率 = 相邻两行的计数差 ÷（`dt_ms/1000 × fps`）。

**编译与交付**：patch `patches/zink-fence-z0-timing.patch`（9,377 B/206 行，`_probe/gen_z0_patch.py` 生成，shadow-tree `git apply --check` + byte-compare **VALIDATION PASS**）+ `patches/zink-tc-disable.patch`（977 B，VALIDATION PASS），两者均已 apply 到源码树（= **cache + Z0 + TC 合并版**）；一键 `build\build_z0.bat -j4`（或 `build\ac_window.bat -j4` 顺带编 gl_smoke）= `ninja_z0.bat`（日志 `build/ninja_z0.log`）→ `postbuild_z0.bat`（`_probe/z0_postbuild.py` 离线 gate：379 exports/ordinal base 7 与原 DLL 逐项一致、系统 DLL only、**5 个诊断 marker**（ZINK_FENCE_STATS/ZINK_POLL_NO_FLUSH/ZINK_POLL_FLUSH_EVERY/ZINK_POLL_SEM_EVERY/ZINK_TC_DISABLE）+ cache marker 全命中、体积 17.30–17.60 MB）→ `dist/z0/`。**重建规模**：`zink_types.h` 被 17/20 个 zink `.c` 包含 → 17 TU 重编 + 3 个归档重链 + DLL 链接，预估 **60–150 s @ -j4**（03:20 的 3 TU+链接实测 18.1 s 含 regen）。
**风险**：计数器非原子（仅 API 线程）、Z3a 限频计数器是 screen 级（多 fence 交错时检测延迟 +N poll）、Z1 使 deferred batch 变长（观察 VRAM）；全部 env 关掉即原行为；回滚换回 `dist/opengl32.dll`。

## 13. per-call 成本方向的 Mesa 侧可能性 `[未验证/预估]`

**已定量的约束**：zink vs 原生 GL = 119.1 vs 249.9 fps（1080p 关光影，GPU 利用率 <15% → CPU 受限）；每帧 2118 GL 调用、89 draws、7.5 sync 轮询。原生 GL 也有每调用成本，所以差异来自 **zink 侧的转译/状态簿记**，摊到每次调用 ≈1.2–1.6 µs。下面按层拆（数字是上限/量级估计，不是实测；括号内是验证方法）：

| 层 | 每调用估算 | x 每帧次数 | 依据 / 证据 | 可做的改动 | 预估收益/风险 |
|---|---|---|---|---|---|
| WGL→glapi dispatch stub（`_mesa_glapi_*`/TLS table） | 20–50 ns | 2118 → **0.04–0.11 ms** | Mesa 生成 stub + 一次间接调用；量级远小于 gap | 无（架构如此） | ~0（微基准可验证） |
| Mesa st（`_mesa_BindTexture/UseProgram/PixelStorei…` 校验+dirty flag） | 0.3–0.8 µs | ~950 状态调用 → **0.3–0.8 ms** | GLCOUNT Top：glPixelStorei 176、glBindTexture 167、glTexParameteri 135、glUniform1fv 116、glActiveTexture 105、glUseProgram 99 | Mesa 侧删冗余校验风险高；更现实的是 mod 侧合并/缓存（Java 侧） | Mesa 侧 0.2–0.6 ms（不安全）→ 应用侧更优 |
| `u_threaded_context` 包装层（`tc_*` ring/atomic/token） | 0.2–0.6 µs | 2118 → **0.4–1.3 ms** | zink 默认建 tc（`st_manager.c:1008-1011` `PIPE_CONTEXT_PREFER_THREADED` → `zink_context.c:6205-6229`）；每次 GL 调用都过 tc 入口 | 加 `ZINK_TC_DISABLE` env 小补丁（~10 行，增量编译 20–60 s）做 A/B；**不能**用 `GALLIUM_THREAD=0` 单独隔离（它同时关 threaded_submit，见 `zink_screen.c:3430-3434`） | 符号未知（异步 vs 包装开销），潜在 0.4–1.3 ms，风险中 |
| zink context state 更新/descriptor 缓存 | 0.2–0.5 µs | ~950 → **0.2–0.5 ms** | 每次 `set_*` 有 dirty/哈希查找（`zink_context.c`） | 去原子/去重复查找（需 profiler 指向再改） | 不明，风险中 |
| debug/assert 层 | 0.05–0.2 µs | 2118 → **0.1–0.4 ms** | **取证已否决**：原实例 DLL 与本构建 DLL 均 **0 处** `Assertion failed`/`_wassert` 字符串（原 DLL 的 13 处 `mesa-26.2.5` 路径与我们的 SPIR-V 路径字符串同源，来自 `vtn_*` 日志/校验宏，不是 assert）→ 实例包也是 NDEBUG，我们的 `b_ndebug=true` **没有额外收益** | 无 | **≈0（假设作废）** |
| 编译选项（/GL LTO、PGO） | — | — | 已论证 §0-5：LTO=全量重编 20–40 min + LNK 风险；PGO 需插桩跑游戏 | 暂不做 | +0–2% |

**结论（诚实）**：per-call gap 的 60–90% 估计落在 st/tc/zink 状态层（Mesa C 代码），但**没有单点改动能一次拿 5%+**；优先级应是：
1. **零编译成本（已降级为对照/回归）**：候选 DLL vs 实例原 DLL 的稳态 fps A/B —— 原意验证 `b_ndebug` 假设，但**取证显示双方都无 assert 字符串**，预期差≈0；仍建议 R1 冒烟后顺手采一组，作为「我们的 DLL 稳态不劣化」的回归证据。
2. **低风险低工作量**：`ZINK_TC_DISABLE` 开关诊断（0.4–1.3 ms 理论空间，符号必须实测）。补丁已就绪：`patches/zink-tc-disable.patch`（977 B）在 `zink_context.c:6205` 给 `PIPE_CONTEXT_PREFER_THREADED` 分支加 `debug_get_bool_option("ZINK_TC_DISABLE", false)`，强制返回非 threaded pipe_context；shadow-tree `git apply --check/-p1`=0 且 APPLIED_OK=True（未 apply 到 live 源码树）。
3. **先测后改（工具已就绪，源码未编译）**：`_probe/gl_smoke.c` 已扩展 `--bench=N` 微基准（单调用 10 个测点 + `mixed2118` 合成帧；分别加载 实例原 DLL / 候选 DLL / 系统 opengl32；支持 `--driver=zink`、`wglGetProcAddress` 回退以测原生 GL），驱动脚本 `_probe/run_percall_bench.bat`、文档 `_probe/PERCALL_BENCH.md`。把「1.2–1.6 µs/次」拆成 dispatch vs st vs zink 侧，避免继续用总量分摊做假设。需要一次编译窗口（exe 编译 2–5 s + 运行约 10–20 s；**会创建 GL context，须 Lead 批准且不与 bench 并发**）。
4. 只有微基准/采样指出具体热点后，才改 zink 热路径；否则容易拿「编译优化」当解药（已证否）。

### 13.1 b_ndebug 假设的验证方法（Lead 要求，工具已实现并出数）

**问题**：候选 DLL 用 `b_ndebug=true`（=MSVC `/DNDEBUG`）编，实例原 DLL 是第三方构建（mmozeiko/build-mesa，配置未知）；`b_ndebug` 差异是否可能白捡 0.5–3%？

**方法（不需要启动游戏、不需要编译，纯 CPU）**：`_probe/ndebug_markers.py` 直接扫两个 DLL 的字节，比对「release/assert 释放相关 marker」的出现次数：

```
C:/Python314/python.exe <项目根目录>\mesaopt\_probe\ndebug_markers.py ^
    <项目根目录>\mesaopt\_probe\orig\opengl32.dll ^
    <项目根目录>\mesaopt\dist\opengl32.dll ^
    --json <项目根目录>\mesaopt\_probe\ndebug_markers.json
```

**实测结果（2026-10-19 03:54，A=实例原 DLL 17,456,128 B / B=候选 DLL 17,421,824 B）** `[已验证]`：

| marker | 类别 | A | B | 判读 |
|---|---|---|---|---|
| `Assertion failed` | MSVC assert 运行时消息 | 0 | 0 | 两边都编译掉了 assert |
| `_wassert` | assert 的 CRT 导入 | 0 | 0 | 都没有 assert 导入 |
| `_CrtDbgReport` | debug CRT | 0 | 0 | 都不是 debug CRT（都是 /MT release） |
| `debug_assert` | Mesa DEBUG 断言 | 0 | 0 | Mesa DEBUG 层都没编进 |
| `NDEBUG` | 宏名 | 0 | 0 | 正常（宏不落二进制） |
| `MESA_DEBUG` | util/debug env 串 | 1 | 1 | 一致 |
| `unreachable` | Mesa unreachable | 2 | 2 | 一致 |
| distinct `mesa-26.2.5` 源路径串 | `__FILE__` | 13 | 13 | 数量一致；内容只是 build 目录不同（orig `D:\a\build-mesa\...` vs ours `<项目根目录>\mesaopt\...`/`../../src/...`） |

**结论 `[已验证: 字符串取证]`**：两个 DLL **都是 NDEBUG**（MSVC 的 `assert()` 在 `/DNDEBUG` 下编译为空，故 assert 运行时字符串/导入为 0）。因此「`b_ndebug` 差异带来 0.5–3%」的假设**预期值下调到 <0.5%**；仍保留 AC 后 A/B 作为终验（成本极低，且能同时证明「我们的 DLL 稳态不劣化」）。

**判据（给 Lead）**：A/B 若出现 ≥1% 稳定差异（同配置、2×2 交替、关 FG），说明差异来自代码布局/链接顺序而非 assert；否则按「候选 DLL 稳态 ≈ 原 DLL」记录，收益只在启动编译（cache，见 §9 R2）。

**caveat**：原 DLL 是第三方非官方构建（§0-7、§2），配置与我们不同；字符串只能证明「assert 层一致」，不能证明源码/编译选项完全相同。

## 14. WGL glthread 补丁风险清单 `[未验证/静态分析]`

**事实基础**：`_mesa_glthread_init()` 全源码唯一调用点 = `src/gallium/frontends/dri/dri_context.c:236`；WGL 的 `src/gallium/frontends/wgl/stw_context.c:248-260`（`st_api_create_context`→`frontend_context`）没有 init；`src/gallium/targets/wgl/wgl.c:264 `DllMain` 也没有 → 现行 WGL 构建里 `mesa_glthread=true` 是 **no-op**（已实测确认）。

**前置条件已满足**：`glthread.c:211-219` 要求 `screen->caps.map_unsynchronized_thread_safe` 且 `allow_mapped_buffers_during_execution`；`zink_screen.c:792-793` 两者均 true（本机 5800H 16 线程/8 大核也不会触发默认关闭）。

**风险**：
1. **与 zink 自己的 threaded context 叠两层队列**：zink 已在 pipe 层异步建 tc（`zink_context.c:6205-6229`），glthread 在 GL 层再批量 → 队列深度×2、批内存增加、flush 时序更复杂；风险中高。
2. **同步点必须补齐**（漏一个就可能延迟一帧/卡死/读回错）：`wglMakeCurrent`（`stw_wgl.c:103`）、`wglSwapBuffers`（:112，帧末必须先 glthread flush 再 `stw_framebuffer_swap_locked`，`stw_framebuffer.c:733`）、`wglDeleteContext`（:81）、`glFlush/glFinish`、`glReadPixels`、`glGetSynciv/glClientWaitSync`、`glMapBuffer`。zink present 链：`wgl.c:194-198` → `zink_screen.c:1897/1929-1939`（flush_frontbuffer 里还要等 §ctx->last_batch_state§ 的 flush fence）→ kopper 异步 present（`zink_kopper.c:551/950-955`）。
3. **destroy 路径**：`_mesa_glthread_destroy` 已在 `st_context.c:896` 被调用（st_destroy_context 内），但 init 若加到 stw_context.c，需确认未 init/异常路径下 destroy 安全（glthread.c 内部有 assert/空转差异）。
4. **map 语义变化**：glthread 会改 `glMapBuffer` 行为（转为临时副本 + 上传），Sodium/AcceleratedRendering 若依赖 persistent map 或频繁 map 可能退化/破坏；本机 MC 210.2 版 Sodium 需实测。
5. **收益预期下调**：MC 主线程本身就是渲染线程，帧内大部分 GL 调用后很快到 swap 等待 —— glthread 主要收益是「命令处理与 app 逻辑重叠」，在 MC 社区经验多为 ±0~5%；此前 `ZINK_FPS_PATHS.md` 的 **+5~15% 是未验证猜测**，应按「不确定、可能为 0」对待。
6. **验证成本**：80–120 行改动 + 增量编译（改 stw_context.c/wgl.c 等 ~20-60 s）+ 至少 2 轮 bench（A/B 必须关 FG）。建议排在 Z0-timing 结论 + per-call 微基准之后，除非 Lead 单独排期。

## 15. 变更记录

- **v1.4**（2026-10-19 03:56，电池冻结期）：按 Lead m01378 最新 AC 顺序重写 §11（①cache DLL fps A/B→②Z0→③Z1/glthread→④per-call 打点）；新增 §13.1「b_ndebug 假设验证方法」+ 工具 `_probe/ndebug_markers.py`（已跑：两 DLL assert runtime 字符串/导入均 0、distinct src 路径串均 13、MESA_DEBUG/unreachable 一致 → 双 NDEBUG，预期差 <0.5%，A/B 留作终验）。
- **v1.3**（2026-10-19 03:50，电池冻结期）：新增 `patches/zink-tc-disable.patch`（977 B，已 shadow 校验未 apply）与 `_probe/gen_tc_patch.ps1`；§10 补全 8 个补丁清单与 `_probe` 工具清单；§11 按 Lead `STATUS.md` AC 顺序（②微基准→⑤R1/R2→⑥Z0）重写并明确「Z0 与 cache 同版」；§13 项 2 补补丁路径。全期遵守冻结纪律：不编译、不跑 GPU、不碰实例。
- **v1.2**（2026-10-19 03:45，电池冻结期）：新增 §12 Z0-timing 补丁就绪（未编译）、§13 per-call 成本方向评估、§14 WGL glthread 风险清单；更新 §0/§11/交付清单（gate 21/21、ABI 证据、7 个补丁、Z0 脚本）。补：assert/b_ndebug 假设经字符串取证否决（两个 DLL 均无 `Assertion failed`）；per-call 微基准工具（gl_smoke --bench）源码与脚本已就绪。
- **v1.1**（2026-10-19 03:2x）：03:07 事故根因 + 修复 + 修复版产物验收（§3.3/§3.4/§6）。
- **v1.0**（2026-10-19 02:5x）：首版（可行性/移植/替代方案/gate/BENCH-REQUEST）。
