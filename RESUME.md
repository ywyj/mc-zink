# RESUME — 断电/新会话恢复入口（2026-10-19 03:50，电池 31%）

## 0. 先读
1) <项目根目录>/STATUS.md（§1 终局目标、§2 实例 canonical、§3 基准协议、§7–§12 实测与结论、§11 AC 后执行顺序）
2) 本文件
3) 各子目录 STATUS-*.md / REPORT.md（zinkperf、mesaopt、packer、dshcore、shaders、stability、createlod、mfg）

## 1. 实例 canonical（<MC目录>/versions/mc-zink）
- config/super_resolution/config.toml: [presentation].backend="OPENGL"（黑屏修复终态；VULKAN 需 srfix 补丁）、upscale_algo="fsr1"、upscale_ratio=1.5、[frame_generation].mode="OFF"、[low_latency] nv_reflex ON
- config/iris.properties: enableShaders=false（bench 会改，跑完要还原）
- config/flywheel-client.toml: backend="flywheel:instancing"（原 DEFAULT 备份在 <项目根目录>/_stage/cfg_backup_flywheel-client.toml）
- options.txt: fullscreen:false、maxFps:260、enableVsync:false、1920x1080@165:24、RD12
- mesa/ 有 opengl32.dll（Mesa 26.2.5 zink，来自 mmozeiko 第三方构建）；mesa.off/ 为原生 GL 对照态
- 恢复脚本：<项目根目录>/_stage/recover.ps1（杀 java、mesa 还原、SR canonical、关光影）

## 2. AC 恢复后第一批命令（按序）
1) 电源确认：`nvidia-smi --query-gpu=power.draw,clocks.sm,pstate --format=csv,noheader` 需 clocks>1000、非 P8
2) Mesa 候选 DLL R1 冒烟：`<项目根目录>\mesaopt\dist\replace.bat`（失败 `rollback.bat`），然后 <项目根目录>/_stage/probe_full.ps1 -Out <项目根目录>/_stage/r1_smoke.png -Shaders 0 -Mesa 1 -Wait 150
3) 微基准重跑（shaders 修好 extra classloader 后）：`node <项目根目录>/_stage/microbench_all.mjs`（6 轮 ~10min，结果 microbench_out/）
4) 干净基线：`node <项目根目录>/_stage/benchrun3.mjs <项目根目录>/_stage/bench_g5b.json <项目根目录>/_stage/bench_results_g5b`（4 变体 ~35min）
5) dshcore-1.3 首启门禁：`<项目根目录>\dshcore\install\install.ps1`（先 -DryRun）→ 启动 → `verify-launch.ps1`（期望 version=1.3.0 mods=11 mixinConfigs=28 injectionPoints=132）
6) MFG：purescale 4 轮（mfg/BENCH_QUEUE_PURESCALE.json）
7) task-9 VULKAN present：srfix 补丁 jar（<项目根目录>/srfix/dist，sha 55951443…）+ stability 的 T0–T2 流程
8) Mesa cache A/B、Z0-timing、GL-DEDUP 原型 A/B

## 3. 未完成/未验证清单（诚实版）
- 转译性能：G5B 干净口径 zink/native = 0.555（CPU-bound，1080p 关光影）；光影口径 0.78（弱证据，电池期作废）
- ns/call 直测未完成（agent classloader bug）；推导值 ≈1.19µs/次
- dshcore 1.2/1.3 未首启；createlod 场景未装未测；Opticores 修复版未装；purescale 未跑；srfix 未实机；Mesa cache DLL 未实测；Z0 未编译
- 打包 v3.1 staging 已有（dist/_v3c-test），未提升正式 dist

## 4. 打包侧恢复步骤（packer，2026-10-19 03:5x，6–10 行）
1) **先读** cfgpack/tools/PROMOTE_CHECKLIST.md §8（dshcore 版本选择矩阵/硬条件/分支）与 REPORT_PACK.md §12–§13；dshcore 1.2.0（sha256 d68b0c2a…64cca8）/1.3.0（sha256 450f3888…9a7aa6）均**未过首启门禁**。
2) **门禁结果分支**：1.3 首启通过 → 用 dshcore-1.3.0.jar 重建；1.3 不过但 1.2 通过 → 用 dshcore-1.2.0.jar；均不过 → 先修（1.2.1/1.3.1）并用 `--no-dshcore` 降级包（109/109）兜底，未过门禁的 jar 一律不许进 dist 根。
3) **一次性重建两套**（判明版本后跑，别白跑；需 Lead CPU 窗口）：`node tools/build-mrpack.mjs --repro --version=3.1.0-test --with-dshcore=<项目根目录>/dshcore/build/dshcore-<版本>.jar`（默认 OPENGL 安全档，SR backend=VULKAN 模板见 REPORT_PACK §10），VULKAN 变体按同节模板另跑一次，再 `node tools/build-absorb-map.mjs` 重建吸收映射。
4) **复验**：`python tools/_check_zip.py` + 看 verify-report.json（期望 117/117：base+nvidia+cross）；`--repro` 必须字节一致；核对 dist/_v3c-test/SHA256SUMS.txt 与文件实际 sha256。
5) **回写**：REPORT_PACK.md §10.3（实测表）/§11（吸收映射）/§13（dshcore 候选状态）与 STATUS-packer.md；把选定 dshcore jar 的 sha256/体积、两个 mrpack 的 sha256/体积/条目数、verify 结果报 Lead。
6) **提升 dist 根**只在 Lead 明确下令后做：先把现有 dist/SHA256SUMS.txt 备份为 SHA256SUMS-v300.txt，再按 PROMOTE_CHECKLIST §1–§7 出 mc-zink-310.mrpack / mc-zink-310-nvidia.mrpack 并复核。
7) 断电保护：每次构建完立即写盘并记录 sha；若 1.3 后续出 1.3.1 重复第 3–5 步（离线 132 项 + FML 解析 + 安装回滚必须全 PASS）后再谈提升。
## 5. ⚠ 2026-10-19 04:10 更新：srfix 实测失败（VULKAN present 仍黑屏，已回滚）
- 结论：dshfix1 消除了 capture-ring 崩溃，但画面仍黑；根因改为「SR 自建 VkSwapchain 与 zink/Mesa WSI 抢同一 HWND」。详见 STATUS.md §14。
- 恢复后若要继续「全程 Vulkan」：先读 STATUS §14 三个选项（A 让 kopper 让出窗口 / B 自写 present 器 / C 维持 GL present），再决定。
- 微基准：`node <项目根目录>/_stage/microbench_all.mjs` 已可用（agent 2.1.1）；4 个 extraOp（getInteger/bufferSubData/texSubImage2D/drawElements）源码已落盘但**未编译**：先 `cmd /c <项目根目录>\shaders\glcount\build.bat` 再跑。
## 6. 2026-10-19 04:2x 状态（电池 6-7%，即将断电）

- dshcore-1.3.0 已装进实例并 PASS 首启门禁（修复 chain mixin 后重建，sha256=beedab08b9feebcb5157c1f6c077e2e9e672b6db9db1c3ff0be24e0638d0ca66，2,548,186B）；mods=56；详见 dshcore/GATE_1.3.0.md。
- 实例 canonical：SR backend=OPENGL / upscale=fsr1 / 1.5 / FG OFF；flywheel backend=DEFAULT；iris enableShaders=false；mesa on；无 java 进程。
- 未完成（AC 后按序）：①微基准 4 个 extraOp（先跑 shaders/glcount/build.bat）②干净 G5B ③IFA/AcceleratedRendering 配置 A/B ④Mesa 缓存 DLL 冒烟 ⑤purescale MFG ⑥Opticores 修复版安装+视觉对照 ⑦createlod 装 jar + 建 DSH-CreateLOD-bench 存档 + 压测 ⑧task-9 全程 Vulkan 三选项决策 ⑨v3.1 打包提升（含 dshcore-1.3.0）。
- 断电恢复：先读 <项目根目录>/STATUS.md（§11 AC 执行顺序、§14 srfix 结论、§15 门禁），再读本文件；实例恢复脚本 <项目根目录>/_stage/recover.ps1。
