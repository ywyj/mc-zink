# 需求路线图与可行性判定 — 2026-10-19（用户新一轮需求）

作者：Lead。背景：本会话实测数据（1080p 前台、zink、56 mod、AC 供电）已在 HANDOFF.md / STATUS.md §16 / REPORT_SR_VULKAN_PRESENT_2026-10-19.md。

## 0. 先给硬事实（决定哪些目标可达）
| 场景（1080p，超平坦“新的世界”） | 实测 median fps | 说明 |
|---|---|---|
| 原生 OpenGL、无光影（mesa=false） | 274 | 本机 Mesa 直连 GL 的天花板 |
| zink、无光影 | 189-208 | 转译开销 ~25-38% |
| 原生 OpenGL、Complementary | 136 | |
| zink、Complementary | 94-111 | 光影再折半 |
| zink、Complementary、SR FSR1 1.5x | 94-106 | 超分基本中性 |
| 原生 Vulkan（VulkanMod） | 未装/未测 | 本包 56 mod 含 Iris/Sable/Flywheel，VulkanMod 兼容风险高 |

- 本机瓶颈：**CPU 单线程**（MC 主线程渲染提交 + zink/GL 调用转发），GPU 占用仅 12-24%。
  证据：GLCOUNT 稳态尾部 100 帧 = 2,118 次 GL 调用/帧、141 次 program bind、533 次 texture bind、89 draws（无光影）。CPU 侧 total≈306-406%，但渲染主线程仍受限。
- 因此「无光影稳定 400-500 fps」在当前硬件+zink+这套 mod 下**不可达**：要达到 400fps 需 2.5ms/帧，而原生 OpenGL 也只有 274fps（3.6ms/帧）。
  可行替代：(a) 1080p 原生 GL 274 已是上限；(b) 降分辨率/降 RD（768p、RD8 可到 ~350-450）；(c) 解锁 maxFps 上限（options.txt maxFps:260 当前会截断，bench 里默认已临时改）；(d) 把 400-500 作为「菜单/低负载场景」目标。

## 1. 需求 → 可执行动作（按性价比排序）

### 1.1 帧率稳定性（用户第一诉求：波动大、初进低帧）
- 初进低帧 = 世界加载/着色器编译/区块构建（zink 下 Mesa 有磁盘 shader cache 缓解，已装 patched DLL 首帧 -6s）。
  - 动作：启动 warmup（进世界后强制预编译 PBR/光影 program，dshcore 内做）；`options.txt` 关闭 vsync；maxFps 解锁 260。
  - 动作：chunk 构建限速（Sodium 默认）+ dynamic-fps 已装（静止降帧）—— 两者冲突需调度：动态 FPS 只在内测 idle 生效。
- 帧时间波动：GPU 降频（本机 AC 下 P0 ok，电池下 P8 会 30fps 硬上限）；CPU 抢占（后台进程）—— dshcore 内做「进程优先级提权 + 关闭后台 GC 抖动」。
  - 实测：bench 中前台+High 优先级能把 cv 从 ~45% 压到 ~30%，仍受世界加载影响。

### 1.2 开光影掉帧太大 → 目标「差距 ≤10%」
- 现实：Complementary 成本来自 shadow pass（双倍 draw + 4 级 shadow cascade）、体积云、SSAO、TAA/后处理。当前差距 2x。
- 达到 ≤10% 的唯一路径：**写一个超轻量光影包**（无 shadow / 无 SSAO / 无 volumetrics / 无 TAA，只保留色调映射+简单水面+雾），把 GPU 成本降到接近非光影管线；代价是观感接近原版+色调。
- 或者选 Photon 的 performance 预设（实测 90.8 vs Complementary 88.2，几乎没有差别）。
- 动作：新建 `shaders/out/DSH-Lite/` 光影包（基于 Complementary 裁剪，保留 IRIS 管线契约，产出 `shaders/` 精简版），并配 SR json。§ 这是本轮最可行的「≤10%」方案。

### 1.3 提高 GPU 占用 / 显存利用率、CPU多核/超线程
- 当前 GPU 占用低是因为 CPU 提交瓶颈，抬高 GPU 占用本身不等于提速。可行：
  - Sodium/AR 的 buffer ring 深度、`maxFps` 解锁、`renderAheadLimit`；
  - zink 侧：MESA 线程参数（`mesa_glthread` 本机 zink 下须关闭——已知），Mesa 提交线程 `ZINK_ASYNC_SUBMIT`? 需实测；
  - CPU：NeoForge `max-tick-time`、`alwaysSetupTerrainOffThread`（Sodium）、实体/方块实体分帧 tick（dshcore 已有 createopt occlusion），chunk 上传线程数调优（sodium options）。
- 显存：Mesa 的 `MESA_SHADER_CACHE_DIR` 已有；纹理压缩（OpenGL 下 compression 可开）、`renderDistance` 与 mipmap 平衡；显存泄漏由 alltheleaks/gpumemleakfix 兜底。

### 1.4 内存管理（按任务管理器占用自适应）
- 动作（dshcore 内实现）：`Runtime.maxMemory/used`、系统可用内存、页面文件，动态调整：
  - 自动降 `renderDistance`/`entityDistance`（可选）、触发 GC 阈值、清理区块缓存（`Minecraft.level.clearCache` 类）。
  - 内存压力高时：降低 chunk 构建线程数、关闭动态 FPS 的扫描线程。
- 已装 modernfix/ferritecore/lithium/alltheleaks，可复用其采样数据（dshcore 读取 JVM MXBean 即可）。

### 1.5 自适应优化系统（用户明确要求）
- 设计：`dshcore` 启动时采集：GPU 名称/显存/驱动（WMI Win32_VideoController）、CPU 型号/核心/HT（Win32_Processor）、RAM 总量/可用、系统盘类型；
  查内置规则表（本机实测标定：NVIDIA+3060 → RD12/12；AMD iGPU → RD6；低内存 → RD8）输出 profile，写 `config/dshcore/adaptive.json`；
  - 手动可改：`config/dshcore/overrides.json`（字段级覆盖，UI 在 dshopt 的配置界面加一页）；
  - 提供「一键回滚/重置」；所有改动记录日志。

### 1.6 物品闪烁修复（渲染问题）
- 候选根因（按概率）：
  1. zink 下 `gl_FragDepth`/深度精度 + Iris shadow 偏移（shadow acne/peter-panning）；
  2. SR FSR1 RCAS sharpness 1.0 在 zink 上过度锐化导致边缘闪烁；
  3. AR（Accelerated Rendering）buffer 绑定恢复失效（配置 `shader_storage_type=RESTORED`）造成绘制穿插；
  4. moreculling/entityculling 误剔 + Sodium 0.8.13 mixin 冲突（Opticores 已修，moreculling 待查）；
  5. Flywheel/Sable（aeronautics）子世界渲染器 `SODIUM_REACHAROUND` 不一致。
- 计划：单变量 A/B（截图×5 帧 + 像素 diff）+ latest.log 关键字（`moreculling`、`sable`、`AR`、`shadow`），逐个关闭定位；修复后回归。

### 1.7 Create / 航空学优化
- createlod jar 已构建未实机验证（Flywheel BlockEntityStorage.createRaw @HEAD 返回 null，规则 L1/L2/L4 剔除 84.2% 机械方块）。需在用户世界 off/on 验证（判据 +30% fps、像素一致）。
- Sable 子世界：`sable-client.toml sub_level_renderer=SODIUM_REACHAROUND` 已知；Aeronautics 的 `gbuffers_veil_aeronautics_*` 着色器可精简（Iris/Aeronautics 兼容）。

### 1.8 压缩进 zink（dshcore）与打包分享
- 合并顺序：adaptive 系统 → 内存/线程调度 → warmup →（createlod 实机后并入）→ 更新 dshcore jar 与 mrpack；
- 分享包：v3.1 mrpack（重扫 `_packdata.json`）+ 增量包（已交付 DSH-Zink-fixes-v3.1.0.zip）；
- 合规：Opticores/SR 修复件是 patch 版 jar，公开分发需注明来源与许可。

## 2. 不可达目标（需用户确认降级）
- 「无光影稳定 400-500 fps」：当前机器+zink 不可达（原生 GL 上限 274）。
- 「光影与非光影差距 ≤10%」：只有超轻量光影包能做到；Complementary/Photon 这类完整光影 10% 无解。
- 「超过原生 Vulkan」：本包未装 VulkanMod，且 Iris/Sable/Flywheel 与 VulkanMod 兼容风险高；zink 当前 ≈ 原生 OpenGL 的 63-71%。

## 3. 建议执行顺序（供下一步）
1. 轻量光影包（目标 ≤10% 差距）+ 与 SR/anti-ghosting 联调；
2. adaptive 系统 + 手动 overrides（纯 CPU 侧，无 bench 依赖，可快速实现）；
3. 闪烁问题单变量定位与修复；
4. createlod 实机验证 + 并入；
5. 帧率稳定性（warmup/优先级/maxFps 解锁/动态FPS 调度冲突）；
6. 全量打包与文档。