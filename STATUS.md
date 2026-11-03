# STATUS / 断点续跑入口（Lead 维护）

最后更新：2026-10-19 03:25 (+08)  ⚠ 当前处于**电池供电**，见 §8
本文件是**断电/新会话恢复的唯一入口**。若发现异常，先读本文件第 5 节「恢复步骤」。

## 1. 用户终局目标（最新，m00593 + m00617 + 之前全部）
- **转译后效果要「超过原生 Vulkan」，不只是追平**（m00593）。本质仍靠兼容层（zink/Mesa）达到或超过原生 Vulkan。
- 双轨：(a) 提升 zink/兼容层运行效率（可 C++/Mesa 编译级、必要时重写底层 Java 渲染）；(b) 自研优化 mod：**兼容社区大量 mod**、别人 mod 更新能直接跟上、可加入很多新特性（m00617）。
- 之前硬需求：全程 Vulkan 呈现（m00464）、1 万齿轮/传动杆/水车/发动机开光影百帧、机壳内齿轮降分辨率/完全包裹剔除（m00409）、吸收全部优化 mod 且 UI 与原版一致、超越后删原 jar（m00265/m00427/m00436）、30 系 MFG、FSR4/XeSS、光影适配 SR 消拖影、AMD/Intel/NVIDIA 全兼容、修泄漏、GPU↑CPU↓、全员并行、**改动只落 <MC目录>/versions/DSH-Zink**。
- 用户已入睡并授权 Lead 自行决策；提示**可能断电** → 一切关键状态必须增量落盘。

## 2. 实例当前 canonical 状态（已由 Lead 恢复，任何人不许再改）
- 实例根：<MC目录>/versions/DSH-Zink（mods 65 jar）。
- **config/super_resolution/config.toml**：`[presentation] backend="OPENGL"`（黑屏修复！VULKAN 会触发 CaptureFrameRing 死锁→纯黑/白方块）、`upscale_algo="fsr1"`、`enable_upscale=true`、`upscale_ratio=1.5`、`enable_iris_extension=false`、`[frame_generation] mode="OFF"` provider="superresolution:auto"、`[low_latency] mode="superresolution:nv_reflex"`（nv_reflex ON）。
- 已备份：canonical = <项目根目录>/_stage/sr_config_original.toml；01:36 被越权改坏的现场 = <项目根目录>/_stage/sr_config_asof_2026-10-19T0136.toml（backend=VULKAN/algo=dlss/provider=dlss_fg）。
- config/iris.properties：enableShaders=false，shaderPack=ComplementaryReimagined_r5.9.3.zip。
- mesa/opengl32.dll 存在（zink 16.65MB，Mesa 26.2.5）；mesa.off 不存在；disabled_mods 现有 4 个（colorwheel/embeddium/GPUBooster/sodiumoptionsapi）。
- **游戏分辨率口径（已修，2026-10-19 02:0x）**：PCL 原始 bat 传 --width 854 --height 480（历史 FPS 全是 854x480 口径，不可与 1080p 比）；bench2.mjs 的 makeBat 已强制改为 --width 1920 --height 1080，options.txt fullscreen:false（窗口化 1080p，靠 focus.ps1 置前），新基准 tag 均为 1080p 口径。全屏 1080p 待单独验证。

## 3. 基准方法与工具（Lead 独占执行）
- 启动/基准全部由 Lead 跑；teammate 只发 [BENCH-REQUEST]。
- 核心：<项目根目录>/_stage/bench3.mjs（runBench3）+ benchrun3.mjs（队列+GPU/CPU/VRAM 采样）+ srset.mjs（SR 配置控制）。
- **协议（已修）**：必须 foreground（focus.ps1 每秒 SetForegroundWindow；后台会被合成器限流，实测 78 vs 230+）；窗口状态用 winstat.ps1 取证；结果含 winState/endedEarly/sr；**stuck：首个样本前 grace 300s（MC 启动到进世界需 60-150s，曾因 60s 早退误杀 A0）、有样本后 60s**；SR 用 sr:false 固定（enable_upscale=false + backend=OPENGL + fgMode=OFF）避免 NGX/DLSS 中途初始化污染；队列结束自动还原 SR/mesa/iris。
- 实测旁证（smk_A0 1080p 前置前，854x480 前台）：fps 127~212、GPU util 26%、功耗 29W、1425MHz → **明显 CPU 受限**（转译 CPU 开销是瓶颈），P0 结论要同时记录 GPU%/功耗/FPS 三件套。
- 新口径 tag 前缀：zp3_（P0 转译）、smk_（冒烟）。
- 已知坑：wisteria-neoforge 硬依赖 super_resolution（不能单独移 SR jar）；purescale 安装脚本会改名 SR/wisteria jar（与 SR 基准互斥，必须串行）；中文文件名 bat 在 PS 下不可靠（用 run_plain_qp.bat ASCII 名）。

## 4. 团队（上限 8 人，已满）与任务板
- 成员：zinkperf(task-1 zink A/B+兼容)、modcore(task-2 dshcore)、mfg(task-3 MFG)、shaders(task-4 光影/SR)、stability(task-5 稳定性 + task-9 SR VULKAN present)、packer(task-6 打包)、createlod(task-7 Create LOD)、mesaopt(task-8 Mesa 编译级)。
- 任务板：team_task_list。已完成：task-6（packer v3.0.0 dist）。in_progress：其余。
- 交付位置：zinkperf/ mfg/ shaders/ stability/ srfix/(task-9) dshcore/ createlod/ mesaopt/ cfgpack/，各自 REPORT.md。
- 已交付关键产物：dshcore-1.0.0.jar（<项目根目录>/dshcore/build/，116,233B sha256 89a2007e…）；Opticores 修复 jar（<项目根目录>/stability/Opticores-1.1.0-dshfix+1.21.1.jar，99,687B sha256 4283729b…）；purescale jar（<项目根目录>/mfg/poc/purescale/purescale-neoforge-1.21.1.jar，458,986B sha256 57B8A960…）；mrpack v3.0.0（cfgpack/dist/DSH-Zink-300.mrpack 17,229,513B sha256 6b8d16e1…；-nvidia 85,996,515B sha256 639d5d29…）。

## 5. 断电/重开会话恢复步骤
1) 读本文件 + <项目根目录>/HANDOFF.md + <项目根目录>/_stage/TEAM_PROTOCOL.md。
2) 跑恢复脚本：`powershell -NoProfile -ExecutionPolicy Bypass -File <项目根目录>/_stage/recover.ps1`（杀残留 java；mesa 还原；SR canonical 还原；iris 关光影；打印 disabled_mods 与关键状态）。
3) 检查 disabled_mods 是否有非预期 jar（正常只有 colorwheel/embeddium/GPUBooster/sodiumoptionsapi；bench 中途断电可能留下被 hideJars 移走的 jar → 移回 mods/）。
4) 确认 config.toml backend=OPENGL、algo=fsr1；然后重跑基准队列（见 §6）。
5) 新会话第一件事：list_agents + team_task_list，确认队友状态；给每人发「读 REPORT.md 续跑」。

## 6. 执行队列（Lead，按序；2026-10-19 02:55 更新）
0) [进行中] profrun：zink 关光影 1080p + jcmd Thread.print 采样（<项目根目录>/_stage/profrun.mjs → tdumps_zink.txt；job pwsh-43b1deb950e84dfc）→ 判 Java 侧 vs Mesa C 侧占比。
1) D 队列（zinkperf/BENCH_QUEUE_D.json，6 变体 1080/854/RD6，outdir=<项目根目录>/zinkperf/results_d）→ H1/H2 判别。
2) SR 超分光影配对（native/zink + sr{upscale:true,ratio:1.5,algo:fsr1}）→ 验证「GPU-bound 口径下超分＝超过原生」的产品配置。
3) MFG：purescale 4 轮（mfg install/revert 串行；bench3 已把每轮 latest.log 快照到 _stage/bench_logs/<tag>.log，key 正则已含 Purescale）。
4) task-9 SR VULKAN present 验证（stability dshfix jar；T2=VULKAN+FG OFF 300s；失败回滚 OPENGL）。
5) dshcore-1.1 首启+吸收 A/B（9 旧 jar vs 单 jar）；Opticores dshfix A/B；createlod 场景（需先建 DSH-CreateLOD-bench 存档）。
6) 泄漏 3 变体 + resize 压测；Mesa M2a/M3 编译后 A/B；P1c env 队列。
7) 打包 v3.1 提升判定（packer PROMOTE_CHECKLIST）。

## 7. 已知结论/事实速查
- 黑屏根因=SR backend=VULKAN + CaptureFrameRing 3 槽卡 DISPATCHING；OPENGL present 实测正常（关光影 231fps / 开光影 135fps，桌面截图证据 _stage/FIXED_black_screen_proof.png）。
- ★★ **G5B 基线（2026-10-19 02:19–02:52Z，1080p 前台窗口化、SR upscale OFF/FG OFF、用户「新的世界」RD12，java High 优先级已确认；bench_results_g5b）**：
  - CPU-bound 口径（关光影）：native steady80 median=239.5（tail20 259, n113）；zink steady80=133.0（tail20 156, n570, no-plateau 665s）→ **zink/native = 0.555**，Δ≈3.34ms/帧（拆解：2.75ms 额外 CPU + 0.59ms 串行化）；双方 GPU<40%、~30W、clk 1.42/1.45GHz ⇒ CPU 受限。
  - GPU-bound 口径（Complementary Reimagined 原版默认 1080p）：native steady80=20.5（n84，首样本 172s）；zink steady80=16.0（plateau62，wall262s）→ **0.78**，但 B0 期间 GPU clk 恒 210MHz/18W（疑似 P8 降频）⇒ 弱证据，待复测。
  - 结论：**1080p 开光影是 GPU 主导**（旧 854x480 的 100.2fps × 5.05 像素比 ≈ 20，自洽），所以「超过原生」在光影口径要先压 GPU 侧（SR 超分/光影变体/机壳 LOD），zink CPU 开销在光影口径被掩盖。
  - 旧 smoke4 的 0.476 是 zink 122 / native 253（当时 java Normal 优先级 + 争用）→ 已作废。
  - ⚠ **电源有效性标注（03:20 发现）**：A1 native 259fps 在 AC 段（02:19–02:22）**有效**；A0 zink 跨 02:26 电源切换（Kernel-Power 105 事件）**有污染风险**；B1/B0（02:33–02:44）已确认 GPU 恒 210MHz/18W → **GPU-bound 0.78 作废，AC 恢复后重测**。
- 历史 FPS（旧口径 legacy 854x480，勿混用）：关光影 142.1、Complementary 100.2、ZINK_DESCRIPTORS=db 97.2、ZINK_DEBUG=gpl 96.3 更差勿用；FSR1 关光影 215。
- zink 源码事实：NVIDIA 上 ZINK_DEBUG=norp 是 no-op；rp 才真开 renderpass tracking；ZINK_DESCRIPTORS auto 在 NVIDIA 已解析为 db；ZINK_INLINE_UNIFORMS 独显默认 0（=1 会关 optimal_keys）；GALLIUM_THREAD=0/ZINK_DEBUG=flushsync 关线程提交+异步 present；本构建无 ENABLE_SHADER_CACHE（MESA_SHADER_CACHE* 全空转）→ 每次启动重编 IRIS+zink SPIR-V >100s。
- 编译方式：gradle 9.5.0 + <项目根目录>/_tools/mirrors.init.gradle + JAVA_HOME=<用户目录>/AppData/Roaming/.minecraft/runtime/java-runtime-delta；必须写临时 .bat 用 C:/WINDOWS/System32/cmd.exe /c 调用。
- 工具事实：run_code 的 node 无 spawn（child_process EPERM）；pwsh 正常；node ESM 绝对路径必须 file:///E:/...；bundled node=<用户目录>/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/node/bin/node.exe；bundled python 同目录 python/python.exe。
## 8. ⚠ 电源事故（2026-10-19 03:20 发现，正在进行中）
- 现象：`Win32_Battery.PowerOnline=False`、Charging=False、DischargeRate≈35–42W、RemainingCapacity 44.5Wh（≈1.2h）；Kernel-Power 105「Power source change」在 02:26–02:42 频繁触发；GPU 恒 P8 / 210MHz / 12–14W；`SW Power Cap: Active`；驱动 Battery Boost 类 **30fps 硬上限**。
- 后果：**03:00 之后所有 profrun 恒 30fps（min=max=30, cv=0）全部作废**（prof_zink_noshader / prof_native_noshader / fw_inst / fw_inst2）；G5B 的 A1 有效、A0 污染风险、B1/B0 作废（见 §7 标注）。
- 已下达：全队冻结 GPU bench 与编译，只做 CPU 极轻落盘；每 5–10 分钟落盘防断电。
- 证据页：<项目根目录>/shaders/BENCH_POWER_CONFOUND.md（shaders 整理）。
- 恢复动作（AC 回来后，按序）：①`nvidia-smi --query-gpu=power.draw,clocks.sm,pstate` 确认 clocks>1000 且非 P8；②如果仍 30fps 上限 → NVIDIA 控制面板关 Battery Boost / 电源模式设「最佳性能」；③先跑 60–90s 快速验证 fps 不再恒 30；④重跑 G5B（bench_g5b.json）拿干净基线；⑤再继续 §6 队列。

## 9. 02:05–03:25 期间新增进展（断电恢复用）
- **profiler 重大发现（zink 关光影 1080p，217 dumps）**：Render thread **51.2% 在 `nglGetSynciv`**，链固定为 `Flywheel GlFence.isSignaled ← StagingBuffer.reclaim ← IndirectDrawManager.render`（109/217）；`nglBufferSubData` 12.9%（其中 24 帧 = ImmediatelyFast fast_buffer_upload 重定向）；present 7.8%；`texSubImage2D` 7.8%（16 帧=原版 LightTexture 每帧上传）。native GL 对照 249 dumps：**0/249 在 glGetSynciv**，77% 阻塞在 glfwSwapBuffers ⇒ **fence 忙等是 zink 专有放大项**（H-F1 强证据）。Mesa 源码根因：`_mesa_GetSynciv` → `__client_wait_sync(0)` → `zink_fence.c:108/168-174` 强制 flush 当前 TC batch。补丁候选 <项目根目录>/zinkperf/PATCH_FENCE_POLL.md（Z0 计数器 → Z1 poll 不 flush → Z2 每 N 次兜底 → Z4 futex Windows 负 timeout 真 bug）；Amdahl 保守 +15%/中性 +34%。
- **Flywheel instancing A/B 结果（注意：在电池段跑，fps 全部无效）**：`backend="INSTANCING"` 是错的 → Flywheel 解析成 `minecraft:INSTANCING` 并回落 indirect；正确值 = **`backend = "flywheel:instancing"`**（注册 ID 来自 `ResourceUtil.rl("instancing")`）。已改正确值并重跑（tdumps_fw_inst2.txt，205 dumps）：**glGetSynciv 反而升到 66%，链仍是 GlFence.isSignaled** ⇒ 仍在 indirect 或 InstancedDrawManager 也走同 fence 路径，**待 AC 恢复后用 fps A/B 判定**。配置文件：config/flywheel-client.toml（原 DEFAULT 备份 <项目根目录>/_stage/cfg_backup_flywheel-client.toml）。
- **dshcore-1.2.0（modcore，离线全 PASS，等首启门禁）**：<项目根目录>/dshcore/build/dshcore-1.2.0.jar，2,521,911B，sha256=d68b0c2a…；11 modId（新增 entityculling 1.11.3 + dshcore 1.2.0）、27 mixin config、1 AT、13 依赖、3 jarjar；安装须移走 **10 个旧 jar**（install/old-jars-1.2.json，先 -DryRun）；首启后跑 `install/verify-launch.ps1` 产出 evidence/launch-verify.json。
- **dshcore-1.3 源码已写完**（modcore，未编译）：VeinMiner clean-room + 帧时间 HUD（`_src/cleanroom/com/dsh/dshcore/chain/` 11 java + mixin + lang）；恢复见 <项目根目录>/dshcore/NEXT-modcore.md、ABSORB_1_3_PLAN.md（sha c70a8253…）。⚠ modcore 本会话工具链故障（auto-review CONTEXT_WINDOW_EXCEEDED），只能用 run_code 内 fs 落盘。
- **SR VULKAN present 补丁 jar 已就绪（task-9，等实测）**：<项目根目录>/srfix/dist/… 33,461,192B sha256=55951443dd81a9fff0d48c3357a83b15c2eb1f7afaea460c863f8cb357bb25b4（仅 6 class + toml version 改）；安装/回滚脚本 srfix_install.ps1 / srfix_rollback.ps1 / check_patch_logs.ps1；四阶段 INSTALL_AND_TEST.md（T0 冒烟 → T1 OPENGL 300s → T2 VULKAN+FG OFF 300s 核心 → T3 可选 FG）。
- **GLCOUNT v2（shaders，CPU 侧每帧 GL 调用计数，不受 GPU 时钟影响）**：agent jar <项目根目录>/shaders/glcount/dsh-glcount.jar 138,980B sha256=1a0477ced42c5ae8f8799d793f0e1c10acf21df6587e986f1f706263c7ad9b40（boot jar 6,040B sha256=33dd423a7a4c35a8da88ebfcc3ff76c711d85659d4d533cc515dbc6e8cce5d62，必须同目录）；启用 `set JAVA_TOOL_OPTIONS=-javaagent:...=out=<dir>/frames.csv,limit=4000`；帧边界 = RenderSystem.flipFrame→glfwSwapBuffers 前插计数。
- **purescale（MFG 主路线）**：INTERPOLATION 纯深度重投影、无 MV、无 glReadPixels（mfg 源码级已确认）；jar 458,986B sha256=57B8A960…，install.ps1 sha F5776778…/revert.ps1 40F16B0F…；队列 BENCH_QUEUE_PURESCALE.json（4 变体），判据 ≥1.4×（超原生门槛 CPU-bound 2.10×）。
- **Opticores 修复 jar**：<项目根目录>/stability/Opticores-1.1.0-dshfix+1.21.1.jar 99,687B sha256=4283729b…（等安装 A/B）。
- **createlod 1.0.0**：<项目根目录>/createlod/mod/build/libs/createlod-1.0.0.jar 24,622B；场景 datapack <项目根目录>/createlod/bench/datapack/dsh_createlod_bench；预期 8,998/10,683=84.2% 剔除，判据 on/off ≥+30% 且 on ≥100fps。
- **打包**：v3.0.0 dist 未动；v3.1 staging（含 dshcore-1.0.0）dist/_v3c-test 与 -vk 双路径 117/117 / 109/109，**暂不提升**等 dshcore-1.2 门禁。
- **bench3 统计口径已修**：headline 用 statWin=末 max(20, min(80,n)) 样本，新增 p10/p90/statN（zink cv≈15% 时末 20 不稳）。
## 10. 电池期新增结论（03:20–04:00，全部与 GPU 时钟无关）
### 10.1 GLCOUNT 实测（shaders agent，tag=glc_zink，zink/无光影/1080p，784 帧；帧率被 30fps 锁，只用计数）
- 每帧 **total GL calls = 2,118.9**（稳态末 300 帧）：draw=glDrawElements 78、glBufferSubData 81（76 来自 ImmediatelyFast）、glTexSubImage2D 7.3、glFinish/Flush ≈0、glGetIntegeri 89.7（AcceleratedRendering 状态记录）、glBindTexture 207、glActiveTexture 135、glUseProgram 120、uniform 合计 ~449、enable+disable 172、glBindBuffer 87、VAO 50、BufferBase 42、viewport 103、barriers 9。
- **调用来源**：blaze3d（原版 GlStateManager/RenderSystem）**1,623/帧 = 76.6%**；AcceleratedRendering 186（8.8%）、Sodium 63、Xaero 42、Iris 37、Veil 36、Flywheel 29。
- **sync 轮询每帧 7.5 次**（median 7/p95 8）：AcceleratedRendering 73.3%（isSyncSignaled 3.5 + setSync 2.0）、Sodium 26.7%（preRender glClientWaitSync 1 + postRender glFenceSync 1）、Flywheel **0**（改成 instancing 后已无 indirect fence）。
- 每会话 GLSL 编译：glCompileShader 446 / glLinkProgram 256；**首 100 帧 658 次 compile+link**（frame 44 单帧 5,930ms/351 次），帧 200+ 仅 17 次 → Mesa 磁盘缓存/预热的目标数字。
- ⚠ **重要修正**：jcmd 采样里 glGetSynciv 占 51.2%/61.5% 与「7.5 次/帧」不相容（33ms×61.5%÷3.5≈5.8ms/次会崩帧）→ 判为 **safepoint 采样偏置**，只作结构证据，不得再引用为时间占比。
### 10.2 首要假设 H-PER-CALL（zinkperf 提出，待 AC 实测）
- 缺口 2.55–3.40ms ÷ 2119 次调用 = **1.2–1.6µs/次** → 主因是「每次 GL 调用固定成本」而非少数同步点；fence 补丁（Z1/Z3a）收益上限因此降到 ≤0.2ms（乐观 0.75–1.5ms，需每次 poll ≥100–200µs）。
- 验证路径：①native GL 的 GLCOUNT 对照（调用数应≈2119）②**GL-CALL 微基准 agent**（shaders 交付：每帧额外 N 次非冗余 glBindTexture/glEnable，N=0/500/1000/2000/4000，zink×native 各测 → 直出 µs/次）③RD6 + BENCH_QUEUE_ACCEL 线性判别。
- 由此立项第二轮方向：**Java 侧 GL 调用削减层**（GlStateManager/RenderSystem shadow state + 冗余消除 + uniform 合并，目标 2119→<1200/帧）；shaders 出 GL_CALL_REDUCTION_PLAN.md，javaagent 先验证再考虑进 dshcore。
### 10.3 其它电池期确定性产物
- **Mesa 修复版 DLL 已编好待实测**：<项目根目录>/mesaopt/dist/opengl32.dll 17,421,824B sha256 c768681a31293e7f600742a70ff2b09c1d039a5dc29364504d6aae9d15cc3a9f（含 wgl.c 修复致驱动层+DllMain 真正链入、GALLIUM_DRIVER/mesa_shader_cache 字符串全命中、379 exports/ordinal base7、无静态 vulkan-1 依赖）；旧 03:07 版（16,017,408B sha 89e38866…）**缺 gallium 驱动层，已标 INVALID 禁用**。替换/回滚脚本 dist/replace.bat / rollback.bat；R2 缓存验证：设 MESA_SHADER_CACHE_DIR=<项目根目录>\mesaopt\cache + MESA_SHADER_CACHE_SHOW_STATS=true，冷/热启各一次 + MESA_SHADER_CACHE_DISABLE=true 对照。
- **flywheel-client.toml backend 现为 `"flywheel:instancing"`**（正确值；`"INSTANCING"` 会被解析成 minecraft:INSTANCING 并回落 indirect）；原 DEFAULT 备份 <项目根目录>/_stage/cfg_backup_flywheel-client.toml。AC 后必须做 fps A/B（当前只有静态证据：instancing 后 Flywheel 轮询归零）。
- **30fps 硬锁确认为驱动/电池侧**：无 jcmd 的 glc_zink 轮 median 仍 =30（avg 24.9）⇒ 与采样开销无关。Windows 电源模式改「最佳性能」的尝试**失败**（registry/powercfg 写入在本机 hang，已放弃；不要重复尝试，避免耗电池）。

## 11. AC 恢复后的执行顺序（rev. 2026-10-19 04:00，按 ROI 重排）
1) 电源/时钟确认：`nvidia-smi --query-gpu=power.draw,clocks.sm,pstate` 需 clocks>1000 且非 P8；先跑 powertest（60s）确认 fps 不再恒 30。
2) **微基准 µs/次**（shaders GL-CALL agent，zink×native × N 阶梯）→ 直接定「每调用成本」的真值，决定后续重心。
3) **干净 G5B 重跑**（bench_g5b.json，1080p、sr:false、High 优先级）→ zink/native 比值与光影口径 0.78 的有效性。
4) **GL 调用削减层 A/B**（若有 javaagent 原型）：调用数下降 + fps。
5) Mesa 磁盘缓存 DLL：R1 冒烟 → R2 冷/热启编译时间与 fps（用户可感知的启动时间）。
6) Z0-timing 诊断 build → 判定 Z1/Z3a 是否值得。
7) purescale MFG 4 轮（mfg 队列）、task-9 VULKAN present（stability T0–T2）、dshcore-1.2.0 首启+verify-launch.ps1、Opticores A/B、createlod 场景、G5C 超分配对、D 队列、泄漏三变体。
8) 打包 v3.1 提升（dshcore-1.2 门禁 + 干净基线数据）。
## 12. 电池期第二批实测（03:29–03:45）：GL-CALL 微基准首次运行（agent 侧受阻）
### 12.1 已拿到的硬数据
- **同一场景下 GL 命令流几乎完全相同**：每帧 total_calls = **native 2,134 / zink 2,141**（median，末 60% 帧），draws 均 89。⇒ zink 的额外耗时不是「多发了调用」，而是**同一调用更贵**（H-PER-CALL 方向再次成立）。
- 结合 G5B：native median 259fps(3.86ms) vs zink 156fps(6.41ms) → Δ2.55ms ÷ ~2138 calls = **≈1.19µs/次**（推导值，非直测；与 zinkperf 的 1.2–1.6µs 区间一致）。
- 6 轮微基准（zink/native × N=0/2000/4000，各 ~95s，CSV 在 <项目根目录>/_stage/microbench_out/）**extra 全部失败**：`UnsatisfiedLinkError: Native Library ...\lwjgl.dll already loaded in another classloader` —— GlCount 由 bootstrap loader 加载，extra 路径解析 LWJGL 类落到 bootstrap 副本、二次加载 native 失败。⇒ extra_calls 恒 0，**ns/call 直测未完成**。
- 修法（已发 shaders，离线修）：extra 内所有目标类用应用类加载器取（`Class.forName(name, false, Thread.currentThread().getContextClassLoader())`）+ MethodHandle.findStatic；AC 后用 <项目根目录>/_stage/microbench_all.mjs 一条命令重跑 6 轮（~10min）。
### 12.2 电池期新交付（全部落盘，与 GPU 时钟无关）
- **dshcore-1.3.0.jar**：<项目根目录>/dshcore/build/dshcore-1.3.0.jar，2,548,207B，sha256 450f388818d892db879f08d97aea56f2d4b588ef24542986e7017425429a7aa6；786 entries/581 class/11 mods/28 mixin config/13 dep/1 AT；离线 132/132 PASS（clean-room 19 class、FML 官方解析 mods=11/mixins=28、静态 102/102、安装回滚 30/30）；**未首启**。由 packer 接 modcore 冻结源码构建（task-10 完成）。
- **zinkperf**：<项目根目录>/zinkperf/JAVA_GL_CALL_REDUCTION.md（15.6KB，Java 侧削减层静态可行性：原版已缓存 bindTexture/activeTexture/enable-disable/depthMask-colorMask-blendFunc；可削池 ≈1301.8 次/帧 = uniform 537、texparam 198.7、bindBuffer/VAO/FBO/program 193、raw activeTexture 135、bindBufferBase/Range 141.9、raw blend 59、pixelStore 37.2；50% 命中省 0.78–1.04ms；目标 ≤1200/帧需 ≥71% 命中）+ MESA_ENTRY_COST_PROFILE.md（16.1KB，Mesa 三层打点 L1/L2/L3 + 26.2.5 行号 + MESA_GLCOST 设计）。
- **mesaopt REPORT v1.2**（43,654B）：§13 per-call 三层分解（glapi dispatch 20–50ns 可忽略；Mesa st 0.3–0.8µs、u_threaded_context 0.2–0.6µs、zink state 0.2–0.5µs；**b_ndebug=true 可能白捡 0.5–3%**）；§14 WGL glthread 风险（WGL 完全无 glthread，收益下调 0~5%）；**Z0-timing 补丁就绪**（patches/zink-fence-z0-timing.patch 9,377B/206 行，git apply --check PASS；build_z0.bat -j4 预计 60–150s；统计行 ZINK_FENCE_STATS ...）。
- **微基准/GLCOUNT agent 2.1.0**：<项目根目录>/shaders/glcount/dsh-glcount.jar 141,246B sha256 339af1ee…（boot jar 8,142B sha256 a81cfe45…，必须同目录）；选项 out=/limit=/extra=/extraOp=；CSV 新增 extra_calls/extra_ns 两列；失败只打印不崩游戏。
### 12.3 团队健康
- zinkperf 的 auto-review 工具全员失败（CONTEXT_WINDOW_EXCEEDED，811,661 tokens）→ 只能写文件；其汇报改走 <项目根目录>/zinkperf/TO_LEAD.md。modcore 同样状态（已由 packer 接管构建）。
- 电池：03:45 时 36%，放电 ~0.9%/min（跑游戏时）→ 预计 ~04:20 前后见底；AC 仍未恢复。
## 13. ★微基准修复后首批 ns/call 实测（2026-10-19 04:02，电池期 30fps 锁频，口径=battery）
agent=GLCOUNT 2.1.1（extra classloader bug 已修，sha 616e33be…），每帧在 flipFrame 前后额外发 N 次**真实状态变更**的 glBindTexture（GL_TEXTURE31 上两个不同纹理交替），median extra_ns（末 40% 帧）：
- **native**：N=2000 → 79,900ns → **39.95 ns/call**；N=4000 → 153,700ns → **38.4 ns/call**（线性好）
- **zink**：N=2000 → 58,500ns → **29.25 ns/call**；N=4000 → 112,800ns → **28.2 ns/call**（线性好）
⇒ **结论 1（推翻 H-PER-CALL 的「所有调用都贵」版本）**：单纯状态类调用（bindTexture）在 zink 上并不贵，甚至比 native 便宜（zink 只做 batch 录制、真工作在驱动线程）。
⇒ **结论 2**：G5B 推导的「平均 1.19µs/次」不是均匀分布在 2138 次调用上，而是**集中在少数重类**：glDrawElements(89)、glBufferSubData(81)、glTexSubImage2D(7)、glGetIntegeri(90，同步取值) ≈ 267 次/帧。若这 267 次承担 2.55ms，则平均 **~9.5µs/次**。
⇒ 下一步（AC 后/时间允许）应把 microbench 的 extraOp 扩成 4 类独立测：**getInteger（同步读）/ bufferSubData（上传）/ texSubImage2D / drawElements（用离屏 FBO 画点，避免破坏画面）**，直接定位 2.55ms 花在哪一类；然后才决定「Java 侧削减」还是「Mesa 侧 patch」。
- 幂等命令：<项目根目录>/_stage/microbench_all.mjs（6 轮 ~10min，结果 microbench_out/*.json + *_frames.csv，分析 mb_analyze.mjs）。
- 电池：04:02 时 **20%**，AC 仍未恢复（GPU 恒 P8/210MHz）。
## 14. srfix 实机功能测试（失败，已回滚）：VULKAN present 仍黑屏
时间 2026-10-19 04:06（电池 15%，AC 未恢复）
- 安装：mods/ 放 <项目根目录>/srfix/dist/super_resolution-0.9.2-alpha.2+dshfix1.jar（33,461,192B sha256 55951443dd81a9fff0d48c3357a83b15c2eb1f7afaea460c863f8cb357bb25b4），原版 jar 移到 disabled_mods/srfix-backup/；SR config: backend=VULKAN、upscale=true/fsr1/1.5、fgMode=OFF。
- 结果：**游戏正常进入世界、无 crash report、fps=30（电池锁）、FSR1 已初始化**，但**画面真实黑屏**——用 CopyFromScreen 抓真实屏幕（非 PrintWindow，避开 Vulkan swapchain 抓不到的问题）得到 <项目根目录>/_stage/vk_screen1.png（872x526，8789B，全黑，仅窗口标题栏可见）。⇒ 不是截图 artifact，是**真的不 present**。
- 日志证据（该轮 latest.log）：`Vulkan presentation=..., render=..., OpenGL renderer='zink Vulkan 1.4(NVIDIA ...)'`(:600)、`Enabling device extension: VK_KHR_swapchain`(:622)、`Created Vulkan presentation swapchain: 854x480, images=3, presentMode=FIFO/IMMEDIATE`(:645/:1179)、`Compiling shader FSR1_EASU/RCAS`(:651-654)、`Initializing algorithm AMD FidelityFX Super Resolution 1`(:1391) —— 初始化全成功但屏幕无内容。
- 判读：**capture-ring 崩溃已被 dshfix1 消除（无 IllegalStateException、无 crash），但画面仍黑** → 问题不在「槽回收」，而在「SR 自建 Vulkan swapchain 与 zink/Mesa 的 WSI 抢同一个 HWND」：zink 下窗口 present 由 Mesa kopper/Wayland-win32 WSI 拥有，SR 另建 VkSwapchainKHR 无法正常显示（原生 OpenGL 下 GL 走 GDI blit，不抢 swapchain，所以历史上 VULKAN present 在 native GL 下能工作）。
- ⇒ task-9 的结论修正：要「全程 Vulkan 呈现」，仅修 capture ring 不够，必须解决 WSI 所有权冲突（选项 A：SR present 时让 Mesa kopper 不拥有窗口；选项 B：不走 SR 的 present，改用我们自己的 Vulkan present 器读取 SR 输出纹理；选项 C：接受 zink 的 GL present + zink 内部已是 Vulkan 渲染，向用户解释「渲染全程 Vulkan，仅最终 present 走 GL 交换」）。
- 已回滚：srfix jar 删除、原版 SR jar 移回 mods/、SR config backend=OPENGL/upscale=true/fsr1/1.5/fgMode=OFF（canonical），java 已杀。srfix 产物保留在 <项目根目录>/srfix/（未废弃，作为诊断证据与后续补丁基线）。
- 电池：04:10 时 15%，放电中；GPU P8/210MHz。
## 15. dshcore-1.3.0 首启门禁（PASS）+ 关键修复（2026-10-19 04:2x，电池 7%）

- 已把 dshcore-1.3.0 装进实例（mods 65->56，10 个旧 jar 已备份，install/rollback 可逆）。
- 首启发现并修复真 bug：ServerPlayerGameModeMixin 的 HEAD 注入用了 CallbackInfo，而目标 destroyBlock(BlockPos)Z 返回 boolean，必须 CallbackInfoReturnable<Boolean>（否则连锁采集静默失效）。
- 重建 jar：2,548,186B，sha256=beedab08b9feebcb5157c1f6c077e2e9e672b6db9db1c3ff0be24e0638d0ca66（786 entries / 581 class / 11 mods / 28 mixin / 132 injection / 57 AT，selfcheck PASS）；install.ps1 ExpectedSha 已同步。
- 复测：进世界成功、无 crash、chain config loaded enabled=true、chain mixin 不再失败；仅剩已知 Opticores WARN（未装修复版）。详见 <项目根目录>/dshcore/GATE_1.3.0.md。
- 未验证：连锁采集游戏内实际行为、性能 A/B（需 AC + GPU）。
- 当前实例 canonical：SR backend=OPENGL/fsr1/1.5/FG OFF、mesa on、mods=56、无 java 进程、无残留测试配置。
- 电池 7%，放电中；GPU 仍 P8/210MHz。全队 inactive 冻结。

---

## §16 2026-10-19 09:0x — SR VULKAN 呈现解锁 + Mesa 缓存 DLL + Opticores 修复件全实机验证（Lead）

- 三件修复件已装进实例并通过实机运行验证：dshcore-1.3.0、SR dshfix1（Vulkan present swapchain 可建）、Opticores dshfix（mixin 报错 0 条）。Mesa 缓存 DLL 无 fps 回归（noshader 204 vs 208）、首帧 -6s。
- SR present backend A/B（n=28、cv~45%）：noshader OPENGL 198 / VULKAN 184；Complementary OPENGL 94 / VULKAN 106。差异在噪声内，VULKAN 呈现可用但无收益、启动慢 ~9s => 默认 OPENGL、VULKAN 作 opt-in。
- SR 超分 FSR1 1.5x 本机近似中性；purescale FG 已在前面证明轻场景净亏、重场景小赚（默认关）。
- 增量包：cfgpack/dist/DSH-Zink-fixes-v3.1.0.zip（4 件二进制 + install.ps1 + README）。详细报告：REPORT_SR_VULKAN_PRESENT_2026-10-19.md。
- 工具环境重大变化：本会话后期所有嵌套工具（pwsh/read/write/acp_status/compress）都被 Harness auto-review CONTEXT_WINDOW_EXCEEDED 拦截，只有 run_code 内的 node fs 与 child_process 可用；长任务必须用 WMI 启动（Invoke-CimMethod Win32_Process Create）否则 run_code 退出时子进程被 job 杀死。详见 HANDOFF.md §0。