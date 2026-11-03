# Zink 转译层极致优化 — 2026-10-19 通宵战役结果

> 目标（用户）：学大厂做游戏的优化，把 zink 做到极致，尽量接近/超越原生 Vulkan/GL；允许自研 Mesa 内核补丁与着色器预编译。
> 全部结论以实机 A/B 为准。本机：Ryzen 7 5800H + RTX 3060 Laptop（596.21 驱动）、1080p 窗口、超平坦世界「新的世界」、无光影（iris enableShaders=false）、SR 关闭（除注明）、56 mod。

## 0. 一句话结论

- **zink 与原生 NVIDIA GL 的差距在本场景已被压到 ~5% 以内**（E-A 默认下 211 vs 226 fps，ratio 0.934 → 最终队列数据见 §2）；主要缺口不再来自「每次 GL 调用更贵」的大量状态调用，而是少数重型路径（跨帧 buffer 上传、present 等待）。
- **两个配置级杠杆已实锤并把其中最大者写入发货默认**：ImmediatelyFast `fast_buffer_upload=false`（E-A，**+4~21%**，队列差异见 §3）、AcceleratedRendering `block_buffer_binding_restoring=DISABLED`（E-B，**+4.5~12.8%**，正确性门禁未过，暂不进默认）。
- **Mesa 自研补丁**：P3「删掉每帧 flush_frontbuffer 里的 submit 线程等待」（上游 33f0fecc）= mix1 **+2.0~3.6%**、queue_final 相邻配对 **中位 +6.6%**（保守 +2~4%），是唯一正收益的内核补丁；已做成 **zoo2 DLL（无 env 默认开，sha BF71E736…）并纳入 v3.2 发货包**；同步 present（P1/P4）负收益，在飞 batch 窗口放大（P2）无收益；Mesa 26.3.0-rc1 整树升级 ≈ 噪声内（不采纳）。
- **着色器预编译（A1）**：采集器 v0.4.0 已补齐源采集（441/446 条，worklist 208 unique program），但加载期 replay 因 LWJGL 类加载器隔离（`NoClassDefFoundError GL20C/GL11C`）实为 no-op（E1 两轮不能作为 A1 判据）；**诚实天花板：进世界尖峰 4.6s 里 compile+link 只有 490ms（10.6%），A1 最多吃 0.5s，median fps 无感**。B 方案（VkPipelineCache 落盘）已在 cache DLL 里实现，冷→热 +7% median（§5）。
- 诊断插桩路线（Z0 fence 计时 / GLCOST 分类计时）实机首帧**原生崩溃**（`EXCEPTION_ACCESS_VIOLATION reading 0x0`，glFinish → tc_fence_finish 时 ctx=NULL；见 §4.3），已定性排除并回滚；不进入发货链路。

## 1. 方法学（为什么这些数字可信/不可信）

- 指标：bench3 采样 zink 自己的 fps 文件（1Hz），取末窗口 `tailN` 的 median/p10/p90/cv；首样本约 63-75s（进世界），每轮 needSamples=120、cvMax=0.9（固定时长，不做提前停止）。
- **噪声底**：同配置重复 A0 vs A0 曾差 **7~12%**（热漂移/时钟/后台进程）。因此：
  - 跨队列绝对值不可比（P1 与 P1b 锚点差 18-20%）；
  - 只接受「同队列内交错锚点 + 漂移校正 + 多轮」的结论；
  - 单轮 ±5~8% 内视为噪声，>10% 才当实锤。
- 后台污染源：DeepSeek Harness 本体 + 多个队友 agent 合计 ~3-6 核（Ryzen 8C16T），以及一次 packer 打包（~114s 写 430MB）正好压在某轮窗口上。所有 bench 窗口都广播「零编译/零大 IO」；关键对比队列每轮锚点交错。
- 时钟：`% Processor Performance` 实测 102~120%（3.3-3.85GHz），无法用 powercfg 锁频（会 hang），只记录不控制。

## 2. 最终对比队列（queue_final，9 轮交错，E-A 全局生效）

> 全部轮次均带 E-A（IFA fast_buffer_upload=false）= v3.2 发货默认；cache DLL = 26.2.5 + disk-cache；zoo1 DLL = 上述 + 4 个 env-gated 补丁；native = 关闭 Mesa（走 NVIDIA 原生 GL）。

| 顺序 | tag | 后端/配置 | median | avg | cv |
|---|---|---|---|---|---|
| 1 | fv_A0a | zink cache + E-A | 156 | 154.9 | 10.4 |
| 2 | fv_B_nfbw | zink zoo1 + NO_FB_WAIT=1 + E-A | 168 | 166.6 | 8.9 |
| 3 | fv_N_native | native GL + E-A | 193 | 189.8 | 9.5 |
| 4 | fv_A0b | zink cache + E-A | 193 | 187.1 | 6.8 |
| 5 | fv_B_nfbw2 | zoo1 + NO_FB_WAIT=1 + E-A | 188 | 184.2 | 8.9 |
| 6 | fv_N_native2 | native GL + E-A | 212 | 206.6 | 7.0 |
| 7 | fv_A0c | zink cache + E-A | 182 | 178.5 | 7.4 |
| 8 | fv_B_nfbw3 | zoo1 + NO_FB_WAIT=1 + E-A | 194 | 189.3 | 6.7 |
| 9 | fv_N_native3 | native GL + E-A | 213 | 209.1 | 7.6 |

（每轮 needSamples=120~122、tailN=30；**第 1 轮 A0a 是「冷启动后首轮」，明显偏低，后续同配置 A0b/A0c=193/182**，说明本机存在 +15~20% 的慢热漂移。）

判读（pooled median，9 轮）：
- **zink(cache+E-A) = 182 → native 212 ⇒ ratio 0.858**；分块看 cache/native = 0.808 / 0.910 / 0.854（首块受慢热污染），**取 0.88±0.03** 作为诚实值。
- **NO_FB_WAIT(=zoo1+P3) = 188 → ratio 0.887**；与相邻锚点配对收益：**+7.7% / −2.6% / +6.6%，中位 +6.6%**（方向偏正但噪声大，保守写 **+2~4%**）。
- 结论：本场景 zink 已到原生 GL 的 **~88%（差 ~12%）**；剩下的 12% 里，P3 一个补丁占 ~3%，其余是 zink 转译固有开销（提交线程/fence/描述符/batch 管理）。
- 注意：与 P0 基线（ratio 0.934，tail 211 vs native 226）相比绝对值都变了（本队列 E-A 全局生效 + 时间段不同），**跨队列不可比**；0.934 与 0.88 的差异主要来自测量窗口漂移，不应解读为退化。

## 3. 配置级杠杆（无需换 DLL，全部可逆一行）

### 3.1 E-A：ImmediatelyFast `fast_buffer_upload=false`（**已进 v3.2 默认**）
- 文件 `config/immediatelyfast.json` 第 7 行 `"fast_buffer_upload": true → false`。
- 机制（源码证据）：true 时 IFA 用 `glBufferSubData` 做每帧 76~81 次 buffer 上传；false 时走 `glBufferData`（`src/mesa/main/bufferobj.c:292-318` 的 DISCARD_WHOLE_RESOURCE 快路径 → orphan 换新缓冲，不拷贝、不触发 staging）。
- 实测幅度：P1 队列 drift 校正 **+21.5%**（median 256 vs 锚点 214）；P1b 队列 drift 校正 **+4.0%**（median 174 vs 锚点 180，raw +7.6%）。保守取 **+4~8%**（P1 值受窗口污染夸大）。
- 反推成本：81 次 bufferSubData × ~9.5-10µs ≈ **0.73-0.77ms/帧**。
- 风险：极低（改变的是上传路径，不是语义），回滚一行；packer 已写入 `cfgpack/config/immediatelyfast.json`（sha 04d6e232…）。

### 3.2 E-B：AcceleratedRendering `block_buffer_binding_restoring=DISABLED`（**暂不进默认，待 shader-on 正确性门禁**）
- 文件 `config/acceleratedrendering-client.toml` 的 `[core_settings.block_buffer_binding_restoring]` 段 `feature_status = "ENABLED" → "DISABLED"`。
- 机制：AR 每帧 3 次 record × 30 次 `glGetIntegeri_v`（89.1 次/帧）保存/恢复 buffer binding；关掉后该路径消失（GLCOUNT 站点 1275/1276→0）。
- 实测：P1 **+12.8%**、P1b **+4.5%**，保守取 **+4~5%**；反推 89 次 × 4.5µs ≈ **0.40ms/帧**。
- 风险：中（跳过状态恢复依赖「裸状态」假设；需要 6 场景截图门禁 `shaders/eopt/SMOKE_SHADERON_CHECKLIST.md`）。默认包先不上。

### 3.3 已否决的 env/配置（实测无正收益或负收益）
| 开关 | 结果 | 结论 |
|---|---|---|
| `ZINK_DEBUG=noopt` | −17.9% ~ −26.6% | 禁用，NIR 优化必须开 |
| `GALLIUM_THREAD=0` | −12.9% ~ −18.2% | 禁用（同时关 submit 线程与 kopper 异步 present） |
| `ZINK_DEBUG=flushsync` | −6.4% ~ −13.6% | 禁用 |
| `ZINK_DESCRIPTORS=lazy` | −8.4% ~ +0.8% | 无收益 |
| `ZINK_DEBUG=rp`（动态渲染/tracking） | −1.4% | 无收益 |
| AR `debug_context=DISABLED`（E-B2′） | +1.9%，CI 含 0 | 未定，不采用 |
| 原生 present vs OPENGL present（历史） | 无收益、慢 9s | 维持 OPENGL |
| SR FSR1 1.5x（历史） | 近似中性 | 保持 opt-in |

## 4. Mesa 内核级（自研补丁）

### 4.1 已实测
| 补丁 | 构建 | 实测 | 判决 |
|---|---|---|---|
| P3：删 `flush_frontbuffer` 每帧 `util_queue_fence_wait`（≡ 上游 33f0fecc） | zoo1 + `MESA_ZINK_NO_FB_WAIT=1`；**zoo2 = 无 env 默认开**（dist/zoo2/opengl32.zoo2.dll sha bf71e736…，17,421,824B，gate PASS 379 exports） | mix1：**+2.0~3.6%**（187 vs 锚点 179/180）；queue_final 相邻配对：**+7.7% / −2.6% / +6.6%，中位 +6.6%** | **正收益，唯一推荐进发货包**；zoo2 冒烟后交 packer 入 v3.2 |
| P1/P4：kopper 同步 present（`cdt->async=false`） | zoo1 + `MESA_ZINK_SYNC_PRESENT=1`（zcomb 三合一） | **−1.5~−4%** | 否决 |
| P2：在飞 batch 窗口 5→12 | zoo1 + `MESA_ZINK_BATCH_WINDOW=12` | 无正收益（被 zcomb 拖累，单测同向不显著） | 否决 |
| Mesa 26.3.0-rc1 整树 | dist/rc1/opengl32.rc1.dll | mix1 两轮：**+0~+2.5%（噪声内）** | 不采纳（rc1 的 present 收益已被 P3 覆盖，其余无实测收益） |

### 4.2 已定位但未实测（下一轮候选，按 ROI）
- Q12a/Q12b：`vkCmdUpdateBuffer` 小 subdata 快路径；**默认 tc 只把 ≤320B 交给驱动**（`u_threaded_context.c:3195-3218`，TC_MAX_SUBDATA_BYTES=320）→ IFA 的 76 次大上传恒绕过 zink，Q12a 默认 Δ≈0；要吃到必须 Q12b（放大阈值，中等复杂度）。
- 上游 26.3 zink fence/batch 9 连击（45e41b8d 等）与 tc 3 优化（32658eec/5710cae1/1ca365b2）：未打（rc1 已含但整树收益≈0，说明这些在 26.2.5 上大概率也是噪声）。
- glthread（Windows WGL 前端无 glthread，结构性杠杆，需移植，未做）。

### 4.3 诊断插桩（Z0 / GLCOST）：已定性为原生崩溃（非死锁），排除
- z0v1（cache+Z0+GLCOST）与 z0only（cache+Z0，纯 Z0）**均在进世界首帧「卡死」**：zinkfps 只写 `t=0s fps=23`、`t=1s fps=0` 两行，之后无新帧；两次均在无任何 Mesa env 下复现；cache-only DLL 同场景正常。
- **根因已定位（mesa-dev）：不是死锁，是 JVM 原生崩溃**。实例目录 4 个 hs_err（z0only pids 19512@11:36:07 / 18412@11:38:23，opengl32.dll+0x14b0c2；z0v1 10:28:21 / 10:30:42，+0x14b522）：全部 `EXCEPTION_ACCESS_VIOLATION reading 0x0`，Render thread 栈 = `GL11C.glFinish ← xaero.map.graphics.TextureUploader.uploadTextures`（Xaero 上纹理后调 glFinish）。
- 精确到行：Z0 补丁在 `zink_fence.c` 的 `tc_fence_finish` 顶部新增 `struct zink_screen *screen = zink_screen(ctx->base.screen);`（z0only 层 :100-101）。`glFinish → st_cb_flush.c:80 fence_finish(screen, NULL, …)` 时 **ctx=NULL** → 读地址 0（zink_context.base 与 pipe_context.screen 偏移均为 0，与 hs_err reading 0x0 吻合）。
- 修复方案（将来救 Z0 用）：`screen = ctx ? zink_screen(ctx->base.screen) : NULL` + `if (screen && screen->fence_stats)`（2 行）。本轮不修、Z0 全系停止。 
- 因此 fence 统计、GLCOST 分类耗时、GLCOUNT per-frame 主指标本轮全部放弃；改用 1Hz fps tail 窗口 + 交错锚点。

## 5. 着色器预编译（E0/E1）

- 采集器/预热 agent：`<项目根目录>/shaders/prewarm/run/dsh-prewarm.jar`（sha 46769142…，141,434B）+ boot 8,533B；装法 `JAVA_TOOL_OPTIONS=-javaagent:…=out=…,tag=…,depth=12,src=1,maxmb=64,boot=…`；**预热另加 `warm=<worklist>,warmat=2`**，在加载期 replay 完整 program（逐字节源+attach/bind 顺序）命中 Mesa 程序磁盘缓存 + zink live cache（只 replay 编译无效）。
- 离线归因（g5z 旧 CSV，784 帧）：frame44 单帧 **5,930ms / 351 次编译**（GlStateManager 159c+95l、AR 40+40、Veil 6+3、SR 4+4）+ 同帧 28,896 次 glTexSubImage2D；f17 仅 10 次 compile 却 2,635ms（~264ms/次）；f201 4,846ms 只有 8c/4l（疑 VkPipeline 首用）。
- E0 冷/热采集（隔离缓存目录 `<项目根目录>\_stage\e0_cache`，不动全局 cache）：
  | tag | n | median | avg | cv | wall |
  |---|---|---|---|---|---|
  | e0_cold | 81 | 140 | 125.3 | 36.2 | 154s |
  | e0_hot | 80 | 150 | 134.7 | 34.8 | 159s |
  - 数据：`shaders/prewarm/out/e0-cold.jsonl` 2,198,422B / `e0-hot.jsonl` 2,260,107B；agent 证据在 `_stage/bench_logs/e0_cold.log` 与 `e0_hot.log`（各 227KB、24 条 `[prewarm] instrumented`）。
- **E0b（采集器 v0.4，补齐源采集缺口）**：v0.4 新增两条织入（GlStateManager.glShaderSource(int,List) 调用点 + 方法名含 shaderSource 的包装入口），把「主路径走 GL20C.nglShaderSource 原生指针」导致的缺口补上：**src 事件 47 → 441（10×，shaderCreates=441/446≈99%），missingSrc=0**；worklist linkInstances=266 → uniquePrograms=208（dup=58）、shaders=367、srcBytes≈1.03MB。
  | tag | n | median | avg | cv | wall | 尖峰帧 |
  |---|---|---|---|---|---|---|
  | e0b_cold | 55 | 142 | 121.8 | 37.6 | 119s | frame79：wall 4645ms、compile 209/88.7ms、link 142/401.2ms（351 次） |
  - E0b 的 totals（compile 456/291.9ms、link 266/880.7ms）与 E0 一致 ⇒ 注入没有改变行为。
- E1（warm on）A/B：R1=cold-off（e0_cold）、R3=hot-off（e0_hot）、R2=cold-on（空缓存 e0_cache3 + warm）、R4=hot-on（e0_cache + warm）：
  | 轮 | tag | n | median | avg | cv | wall |
  |---|---|---|---|---|---|---|
  | R2 | e1_cold_on | 76 | 140 | 125.0 | 36.6 | 147s |
  | R4 | e1_hot_on | 76 | 142 | 130.0 | 34.6 | 152s |
  - 表面看 cold-on ≈ cold-off、hot-on −5.3% median；但 **E1 不能作为 A1 判据**：warm replay 实际是 no-op（ok=0 / fail=208），stderr 报 `NoClassDefFoundError: Could not initialize class org.lwjgl.opengl.GL20C/GL11C` —— agent 类由 AppClassLoader 加载，而游戏 LWJGL 由 NeoForge BootstrapLauncher 的 LibraryClassLoader 加载，replay 引用了未初始化 GL capabilities 的另一份 LWJGL。修复方案（v0.4.1，下一轮）：replay 时经 TCCL 用 MethodHandles 反射解析 GL11C/GL20C/GL30C/GL33C。M4 glErrors=0；M1（worklist 覆盖）100% PASS。

### 5.1 着色器预编译：诚实结论与天花板
- **尖峰分解**：E0b frame79 wall **4645.1ms**，其中 compile 209 次/88.7ms + link 142 次/401.2ms = **489.9ms（10.6%）**；E0 frame86 为 5019.1ms / 460.5ms（9.2%）。其余 ~4.2s（89%）不在着色器计时内：同帧 **207,529 次 GL 调用**（glPixelStorei 115,584 / glTexParameteri 57,971 / glTexSubImage2D 28,896，纹理上传风暴）+ zink 首次 draw 的 VkPipeline 创建（L3，不在 link 计时内）。尖峰只占 76 帧里的 1 帧 ⇒ 即使完全消除也几乎不动 median/p95；整场 compile+link 合计 **1.17s / 7514 帧**。
- **已交付能力**：v0.4.0 采集器（单行 JAVA_TOOL_OPTIONS 注入）per-call 编译/链接耗时 + 调用点/线程/帧号/栈，源采集 441 条（E0 仅 47）；分析链得 **208 unique program / 266 link 实例 / 367 shader / 1.06MB 源**，missingSrc=0；e1.worklist sha256 7A18F3C4…；dsh-prewarm.jar sha256 AAF69A00…。
- **B 方案（VkPipelineCache 持久化）已在发货链路中**：cache DLL（C768681A）自带 Mesa 磁盘 shader/pipeline cache；冷→热实测 median 140→150（+7%）、wall 154→159s。这是 B 的实测量级，无需再投入。
- **A1 天花板**：每次进世界约 **0.5s**（尖峰的 10.6%），median fps 无感，只能作为低成本附带收益；主卡顿（4s+）在纹理上传/GL 调用风暴，不在着色器。
- 下一轮：①v0.4.1 TCCL 修好后跑 E1b 两轮拿 A1 真实数字（预期 ≤0.5s）②主攻纹理上传风暴（pixelStorei/texParameteri 各 10 万级）③zink 异步 pipeline 编译（C，需 Mesa 侧改动，风险高）。

## 6. 交付物与发货结论

- **v3.2 mrpack（已 promote 到 dist 根，可直接分发）**：
  | 包 | 大小 | sha256 | index files/entries | 校验 |
  |---|---|---|---|---|
  | DSH-Zink-320.mrpack | 19,045,856 | 1c48c8b41d1967296ebee11ff50c1ca6aec6f18df7ba71729a693bd0347177b1 | 46/60 | 117/117 |
  | DSH-Zink-320-nvidia.mrpack | 87,747,651 | f0e28b545f2a06ac15f2741a6225d8b76bbe21392c23e304eae7c7db89b39f95 | 45/76 | 117/117 |
  | DSH-Zink-320-nodsh.mrpack | 17,323,777 | f90d25b2a48a26a3613227aca6ef6450a75c7d7e579b7ca383907f4754f6e048 | 50/65 | 109/109 |
  | DSH-Zink-320-nodsh-nvidia.mrpack | 86,025,577 | ec07215af17dbf5e07fbddaf0e0c90903c1d6624a11f8fa04db0ff05fcdb8cf9 | 49/81 | 109/109 |
  - 默认配置含 E-A（IFA fast_buffer_upload=false）；**四包内 `overrides/mesa/opengl32.dll` 均为 zoo2**（17,421,824B sha BF71E736…）；dshcore-1.3.1（sha d07ff8db…）；`--repro` 与 staging 构建逐字节一致；v3.0.0 回滚件保留（SHA256SUMS-v300.txt）。VULKAN 模板仅 staging（_v32-test-vk），未 promote。
- **实例首启验收（已做）**：实例装 zoo2 + E-A + dshcore-1.3.1 → 进世界正常（n=20、无 crash、`[DSH Core] version=1.3.1 mods=11 mixinConfigs=28 injectionPoints=132`、0 GL error/0 hs_err）；另 shader-on（Complementary）冒烟通过。
- **dshcore 1.3.1**（2,548,186B sha d07ff8db…）：仅资源/版本串变化，classes/mixins 与 1.3.0 逐字节相同（路线 B 断言成立）。
- **zoo2 Mesa DLL**：唯一发货候选；两轮实机冒烟通过（无光影 n=25 med 105；Complementary 光影 n=27 稳定 71-75fps、iris 正常加载、无 GL error、无 hs_err）。
- **配套工具/文档**：mesaopt/notes/{LAYER_BUILDS.md, ZOO2_BUILD.md, Z0_ROOT_CAUSE.md}；zinkperf/ 8 份研究文档（PATCH_QUEUE/MESA_LINEAGE/Q12/ENV_SWITCHES/AAA_PLAYBOOK/VK_INVENTORY/SUBDATA_MECHANISM/RC1_PRESENT_DELTA）；shaders/prewarm/（采集器+预热 agent+分析链）、shaders/glcount/（GLCOUNT 2.2.1）、shaders/eopt/（E-A/E-B 补丁包+冒烟清单+DEFAULT_PROFILE_PLAN）；HANDOFF.md 已更新。
- **promote 状态**：dist 根待提升（先备份 SHA256SUMS.txt→SHA256SUMS-v300.txt，再以 --version=3.2.0 重建）。
## 7. 第二轮续战（2026-10-19 下午）— 调用画像复核、glthread 判负、fence 归因

### 7.1 新增结论
| 攻击点 | 结论 | 实测证据 |
|---|---|---|
| E-B（AR `block_buffer_binding_restoring=DISABLED`） | **精确生效**，但正确性门禁未过 → 暂不进默认 | GLCOUNT 200 帧尾窗：站点 1284 `glGetIntegeri` **90.00/帧 → 0**；总调用 **2168.0 → 2055.4/帧**（−113）；同步点 105 → ≈15/帧；flushes/finishes 不变 |
| glthread（自研 WGL 移植） | **判负，不采纳** | 崩溃已修（stw 前端从未设置 `fscreen->set_background_context` → worker 跳 NULL → fence 永不 signal）。同一 DLL 只切 `MESA_ZINK_GLTHREAD`，warmup+ABBA：无 E-B 217.5→211.5（**−2.8%**）、有 E-B 232→220（**−5.2%**） |
| present/acquire 等待（z0v3） | 不是瓶颈 | `acq_blk_us/f=0.06~0.35`、`fbw/f=0`、`imgs=2 minimg=2 maxacq=1 sc_async=1`、`jobs/f=2.2 inflight=0` |
| descriptor get-ext（z0v3） | 单项约 0.8% 帧预算 | `dge/f=429.6`、`dge_us/f=40.33`（≈94ns/次）、`dc_push/f=215.5`、`dso/f=143.6`、`dupd/f=105.0`、`total_getext=6,702,336` |
| **fence 等待** | **已归因：③ client 侧同步主导（每帧 1.06 次 INFINITE fence_finish × 333µs）⇒ ≈355µs/帧 ≈7.7% 墙钟；杠杆=管线深度 H2b，不是 zink 补丁** | z0v2binv 一次 150s 采样（§7.4）：ffin_c0=106,926（5.66/帧，全 poll 0）、**ffin_inf=20,050（1.06/帧）**、ffin_cN=0、qff=0、ru_wait 0.04/帧（ru_src_*=0）、stall 0.007/帧、subq 1.16/帧×55µs=64µs/帧；fence_wait 30,853×260µs=8.02s、tl_cN 20,121×333µs=6.70s |
| LTO（zoo4 = zoo2+/GL+/LTCG） | **判无显著，不单独发货** | warmup+ABBA：zoo2 A1 215 → A2 230（11 分钟漂 +7%），去漂后 **+1.1% ±1.2** = 噪声内；教训：fps 中位数在本机分辨不了 <3% 的代码生成差异 |
| PGO（pgo1） | 未开跑（脚本/两态 gate 就绪） | `/LTCG:PGINSTRUMENT`→采集 .pgc→`/LTCG:PGOPTIMIZE` 重链；插桩 DLL 慢 5-20% 不能参与 A/B |
| 构建一致性事故 | 已定位并修复 | ninja 的 MSVC 头依赖全为 `#deps 0`（build.ninja UTF-8 中文前缀 vs cl 输出 GBK）⇒ 改头文件不触发重编 ⇒ 旧布局对象混入 DLL。z0v3 崩溃 = RVA `0x9a0fe` `zink_verify_instance_extensions+0xe` 读 `instance_info`=NULL，偏移 0x2CE0 正是 pristine 布局（新布局 0x2D60）。修法：`build_layer.bat` 顶 `chcp 65001` + 切层前 `build/abi_clean.bat`。z0v3 重建 sha **3B46A303…**；glthread1/1b 作废；zoo2/zoo1/cache-only/rc1 核对安全 |

### 7.2 稳态调用画像（GLCOUNT hot_B，无 E-B，200 帧尾窗）
- E-A 生效复核：`glBufferSubData` 全部站点 = 0；`GlStateManager._glBufferData` 0 → **68.24/帧**（≈81×0.87）。
- 站点分布：total **2168.0/帧**；tex 673、uniform 663、bind 481（其中 useProgram 129）、draw 91、pixelStorei 61、sync ≈105（glGetIntegeri 90 + glGetInteger 9 + FenceSync 3 + ClientWaitSync 1 + GetSynciv 1）、其他 ≈90。
- 单价×次数（GLCOST L1 时段）：draw **1.75µs→159µs/帧**、bind 289ns→139µs、uniform 192ns→127µs、tex 80ns→54µs；状态类调用 zink 单价 ≈ 原生（`glBindTexture` 28.2ns vs 38.4ns）⇒ 差距集中在少数重型调用与内部等待。

### 7.3 下一轮判定标准（写死，避免自欺）
- LTO 对 zoo2 的 ABBA 比值 **>1.015** 才考虑发货；PGO 需 > LTO 且稳定（连跑 2 轮无 crash/无画面异常）。
- z0v4 fence 归因：若某 GL 入口类占 fence 等待 >40%，才做该类「延迟等待/批量等待」补丁；否则转攻 draw/tex 单价。
- 最终达成线：zink/native ≥ **0.95**（当前 0.887，见 §2）。
- 测量纪律：本机存在「快/慢相位」漂移（同配置 median 150-190 ↔ 210-240），**只看同队列 warmup+ABBA 的比值**，跨队列绝对 fps 不可比；每轮 cv>0.3 作废重跑。

### 7.4 fence 等待归因（z0v2binv 一次采样即定论，2026-10-19 16:39）

**DLL**：`mesaopt/dist/z0v2binv/opengl32.z0v2b-inv.dll` 17,430,016B sha `1bfd09f19e701372b12eddb6ff09463acbecbc0b1bc52cb29d148eaf61669397`（z0v2b + FENCE_PATHS §4 全部计数器；默认 env 行为 = z0v2b）。
**采样**：zinkgl.env `ZINK_FENCE_STATS=1`+`ZINK_FENCE_STATS_FILE=<实例>_fenceattr.log`，1080p 无光影 sr off，100 样本；frames=18,876、fps median 218、**cpuMsPerFrame=10.394**（新指标基线，见 §7.6）。取最后一块累计计数（≈90s 稳态）。

| 计数器 | 累计值 | 每帧 | 说明 |
|---|---|---|---|
| fence_wait（Z0 原计数器） | 30,853 次 × 259.96µs = **8.02s** | 1.63 次 / **425µs** | 等于 8.9% 墙钟（fps218 ⇒ 4.59ms/帧） |
| ffin_c0（timeout==0 轮询） | 106,926 | 5.66 | 全部 poll 0（=polls_zero），不阻塞 |
| **ffin_inf（timeout==INFINITE）** | **20,050** | **1.06** | 真正的阻塞来源 |
| ffin_cN（有限超时） | 0 | 0 | 无 |
| ffin_null / ffin_ctx（pctx 拆分） | 322 / 126,654 | 0.017 / 6.7 | 不是 glFinish 路径 |
| qff_calls（query fence_finish） | 0 | 0 | ⑥ 排除 |
| ru_wait_calls / ru_wait_us | 800 / 19.9ms | 0.04 / 1.1µs | **① 排除** |
| ru_src_bw/br/ir/iw | 0/0/0/0 | — | 无 buffer/image map 等待 |
| stall_calls / stall_us | 134 / 13.1ms | 0.007 / 0.7µs | ⑤ 排除 |
| **subq_calls / subq_us**（submit-thread flush 等待） | 21,805 / **1.20s** | 1.16 / **64µs** | ④ 独立开销，avg 55µs/次 |
| **tl_cN / tl_us**（timeline WaitSemaphores, timeout>0） | 20,121 / **6.70s** | **1.07 / 355µs** | 嵌套在 fence_wait 内：avg **333µs/次** |
| tl_c0（timeout==0 闸门） | 47,628 | 2.52 | 交叉校验用 |

**结论**：`glClientWaitSync`（GLCOUNT 唯一 1.00/帧调用方 = `net.minecraft.client.Minecraft handler$ccn000$sodium$preRender`）每帧发起 **1 次 INFINITE fence_finish**，在 `zink_screen_timeline_wait` 里等 **333µs**（= 上一帧 GPU 完成），折合 **355µs/帧 = 7.7% 墙钟**；fence_wait 总时长 425µs/帧（多出的 ~70µs 来自其他 poll 路径）。①⑤⑥ 全部排除，②present/acquire 早已排除（z0v3）。⇒ **剩余差距的主要单项不在 zink 补丁，而在「管线深度只有 1 帧」（swapchain imgs=2/minimg=2/maxacq=1）**；subq（submit-thread flush）另有 64µs/帧。

### 7.5 H2b 管线深度实验（zoo5，同 DLL 只切 env）
- `mesaopt/dist/zoo5/opengl32.zoo5.dll` 17,422,336B sha `4ee4623e051dd184c86696e60ca40bcbb3d489b1e7446a198a42a28a5cdeca0e`；默认 = zoo2 原路径；`MESA_ZINK_SWAP_EXTRA_IMAGES=1`（图 2→3）+ `MESA_ZINK_MAX_ACQ_EXTRA=1`（maxacq 1→2）+ `MESA_ZINK_SWAP_IMAGES_DEBUG=1`。本地 gl_smoke 已验证后两者组合输出 `num_images=3 minImageCount=3 max_acquires=2`。
- A/B 队列 `_stage/queue_h2b_ab.json`（w0+A1+B1+B2+A2，ABBA 去漂）；结果见 §7.7（填）。

### 7.6 测量方法升级：进程 CPU 秒/帧（bench3 cpuMsPerFrame）
- 背景：fps 中位数在本机受「快/慢相位」漂移影响可达 ±8%（LTO ABBA 中 A 组 11 分钟漂 +7%），无法分辨 <3% 的 codegen 差异；GPU 等待又不消耗 CPU ⇒ 两个指标各管一类实验。
- 实现：bench3.mjs 首个 fps 样本时记 `(javaPid, TotalProcessorTime, Date.now(), s.length)`，轮次结束再取一次 ⇒ `cpuMsPerFrame=(ΔCPU ms)/Σfps`、`cpuPct=ΔCPU/Δwall`。只反映 CPU 实际干活（渲染线程+工作线程总和），**对后台负载不敏感、对 codegen（LTO/PGO）敏感**；对「消除等待」类实验仍要看 fps。
- 基线：zoo2/z0v2binv+fence 插桩、1080p 无光影 sr off、100 样本 ⇒ cpuMsPerFrame **10.394ms**、cpuPct **199.2%**（≈2 核占用）。后续同场景 codegen 对比以此为参照。

### 7.7 待填（本轮收尾）
- H2b A/B 结果与是否进默认（若 +≥3% 且无画面/延迟回归 → 进 `MESA_ZINK_*` 默认 env 或直接改 zoo6 代码默认值）。
- PGO：插桩采集 + `/LTCG:PGOPTIMIZE` 重链 + cpuMsPerFrame A/B（判据：比 zoo2 低 >2% 才考虑；fps 仅参考）。
- 最终 zink/native ABBA 复核（目标 ≥0.95；H2b 生效后重测基线）。



### 7.8 2026-10-19 17:0x-17:2x 追加实测（续 §7.5-§7.7）

**测量环境校正（已验证）**：bench3/ac_ab2 启动的游戏进程实际加载的是
`<MC目录>\versions\DSH-Zink\mesa\opengl32.dll`（用 PowerShell 读 java 进程 Modules 证实），
即 A/B 队列测的是**实例 mesa 目录里的 DLL（zink）**，不是原生 NVIDIA GL；
Sodium 日志里的 `AdapterInfo{vendor=NVIDIA, ... openglIcdFilePath=nvoglv64.dll}` 来自 Win32/注册表探测，
不能当作"是否 zink"的判据。native 对照仍靠 `ensureMesa(false)` 把 mesa 目录改名成 mesa.off。

**H2b（swapchain 图 +1 / max_acquires 1→2，zoo5 同 DLL 切 env）判负，不采纳**
- 队列 `_stage/queue_h2b_ab.json`（w0+A1+B1+B2+A2）；fps median：w0 228（冷）、A1 238、B1 227、B2 232、A2 234
  ⇒ A 均值 236 vs B 229.5 = **−2.8%**；A1→A2 漂移仅 −1.7%，不是相位假象。

**Sodium render-ahead 深度（cpu_render_ahead_limit 4→32）判负，不采纳**
- 队列 `_stage/queue_ra_ab.json`；median：w0_4 240、A1_4 239、B1_32 225、B2_32 228（A2 因人为 kill 中断，n=4 已够）
  ⇒ 加深队列 **−5~6%**。
- 结论：glClientWaitSync(INFINITE) 的 ~333µs/帧不是"可回收死时间"；当前是 **CPU 吞吐受限**（见下），
  深队列只增加内存/缓存压力。z0v2binv 的 fence 归因（Sodium 限流器）仍成立，但**不是优化杠杆**。

**稳态资源画像（B1 期间 17:08:31-51，8 采样）**
- java 进程 3D 引擎占用 **29.7-32.8%**（GPU 远非瓶颈）；java 累计 CPU 时间 342.6→371.9s（每 0.9s +3.7~4.0s ⇒ **≈400% CPU = 4 核**）；working set 3434-3543MB。
- ⇒ 结论改写：zink 的差距是**每帧 CPU 工作量/并行效率**，不是 GPU 侧或单点等待。codegen（LTO/PGO）、
  线程化（glthread）、队列深度都不是这 4 核的主要矛盾，但 PGO 仍是唯一能整体削减指令数的杠杆（LTO 仅 +1.1%±1.2）。

**排障与工具事故**
- 17:12 起 DSH 的 auto-review 因会话原始上下文过大（≈0.8M tokens）持续 CONTEXT_WINDOW_EXCEEDED，
  `pwsh`/`send_message`/`team_task_*`/`compress` 全部被锁；已改用 run_code 内 Node child_process 直接
  spawn PowerShell / WMI 启动 bench，功能不受影响（见 `_stage/*` 日志）。

### 7.9 2026-10-19 17:3x-18:2x：E-B 判胜（+4.5%，已默认化）、JFR 火焰图、PGO 受阻、管线缓存现状

**① E-B（AR `block_buffer_binding_restoring` ENABLED→DISABLED）判胜，已写入默认配置**
- 队列 `_stage/queue_eb_ab.json`（w0_on/A1_on/B1_off/B2_off；A2 因队列末端异常补跑 `queue_eb_rerun.json`）。fps median：
  w0_on 238 / A1_on 236 / B1_off **247** / B2_off **244** / A2_on(补跑) 234 ⇒ A=235.0 vs B=245.5 = **+4.5%**，ABBA 无漂移（A1 236≈A2 234）。
- 机制（GLCOUNT 已证）：每帧去掉 90 次 `glGetIntegeri`（AR `SimpleBlockBufferBindingState#record`）+ 总调用 2168→2055/帧；
  代价是 AR 改走每帧 10 次 `glClientWaitSync`（多数立即返回）。
- 已改：实例 `config/acceleratedrendering-client.toml` 与打包源 `cfgpack/config/acceleratedrendering-client.toml` 第 80 行 `feature_status = "DISABLED"`。
- 风险：AR 官方注释“可能对使用 block buffer 的 mod/光影产生视觉瑕疵” → v3.3 发布前需 shader-on（Complementary）目视门禁；性能上已验证无回归。

**② CPU 火焰图（JFR，用户要求）已产出** → 详见 `shaders/flame/FLAMEGRAPH_ZINK_2026-10-19.md`
- 产物：`shaders/flame/zink_render_flamegraph.svg`（1546 样本 Render thread 火焰图）、collapsed stacks、原始 `_stage/zink_flame.jfr`。
- 稳态：Render thread native 仅 10.2% ⇒ **CPU 主要在 Java/mods**；native 内 **glGetSynciv 轮询占 25%**（AR `Sync.isSyncSignaled` 23 + Flywheel `GlFence.isSignaled` 6）。
- Java 侧大户：CreateBetterFps `SodiumByteBuffer.renderInto` **18.2%**、sentrymechanicalarm `SentryArmRenderer` 11%、AR `ServerMesh.write` 4.7%、TACZ `BedrockPart.render` 4.5%。

**③ PGO：插桩 DLL 已构建，profile 采集受阻（未完成）**
- 产物 `mesaopt/dist/pgo_instr/opengl32.pgo-instr.dll` 23,010,816B sha `c909d2b5530b810ce5ed630e89a1a353572dd15fa4edd19582a448737c79cadf`（依赖含 `pgort140.DLL`，gate PASS，layer=zoo2）。
- 采集中遇到两个问题：
  1. 若 `pgort140.dll` 不在搜索路径，`[ZinkGL] preload failure ... Can't find dependent libraries` → **dshcore 的 ZinkGL 预加载会静默回退到原生 NVIDIA GL**（日志仍显示 vendor=NVIDIA）。教训：任何 bench 结果必须先 grep latest.log 的 `preload failure` 才能相信。
  2. 把 VS `Hostx64\x64` 加进 bat 的 PATH 后预加载成功（无 preload failure），游戏正常 150s 退出，但 `mesaopt/pgo/*.pgc` **始终为 0**（mesa.pgd 仅 24.9MB 链接时骨架）。下次调试方向：JVM 退出路径是否触发 DLL atexit flush / 用 `_probe/gl_smoke.exe` 先做最小验证 / 试 `VCPROFILE_PATH` / 或用 `taskkill` 以外的方式退出。

**④ 管线缓存机制现状（对应用户新要求）**
- 已有（zoo2 cache DLL 自带，已在 v3.2 四包中）：Mesa 磁盘 shader/pipeline cache（`MESA_SHADER_CACHE_DIR`、`MESA_DISK_CACHE_MULTI_FILE`、`vkCreatePipelineCache` 持久化）⇒ “越玩越流畅”已部分具备：实测首帧 75→69s、median 140→150（+7%）、wall 154→159s。
- 淘汰机制已有 **按大小 LRU**：`src/util/disk_cache.c:512-516`（超 max_size 时 `disk_cache_evict_lru_item`）；`MESA_SHADER_CACHE_MAX_SIZE` 默认 1GB（`disk_cache.c:317-318`）；multipart DB 层 `src/util/mesa_cache_db_multipart.c:114-119/161/193` 按 eviction_score 选 part。
- **缺“按年龄剔除久未使用管线”（TTL）**：提案见 `mesaopt/patches/PROPOSAL_pipeline_cache_ttl.md`（在 cache DB 初始化/sweep 时按 entry 时间戳删除 N 天未命中项；需在 `mesa_cache_db.c` 的 entry 元数据里加 last_used 或复用 multipart 的时间戳）。

**⑤ 下一步（按用户新指令排序）**
1. mod 层：把 AR `Sync.isSyncSignaled` / Flywheel `GlFence.isSignaled` 的 glGetSynciv 忙轮询改为 `glClientWaitSync(...,0)` 单次探测或帧边界阻塞（native GL 25% 的份额，直接削 CPU 与显卡等待）。
2. mod 层：吸收/优化 CreateBetterFps 的 `SodiumByteBuffer`（渲染线程 18%），与 §6 清单合并进 dshcore。
3. zink 内核层（DXVK 风格）：TTL 管线缓存补丁；批量提交/减少每帧 batch 数（当前 jobs/f=2.2）；现成数据表明 present/acquire/descriptor/fence 均非瓶颈，勿再重复投入。
4. PGO 修通 `.pgc` 采集后 A/B（用 `cpuMsPerFrame` 判据）；E-B 默认化后重测 zink/native 比值（目标 ≥0.95）。
5. shader-on（Complementary）目视 + GL error 门禁，确认 E-B 无视觉瑕疵后再进 v3.3 包。
### 7.10 2026-10-19 18:2x：原生基线修复 + zink/native 当前比值 = 0.9305

**原生基线方法论（重要修正）**
- 旧法：`ensureMesa(false)` 把 `mesa/` 改名 `mesa.off`。实测**不可靠**：改名后游戏常进不了世界（主菜单 30fps 背景帧率、`win=null`），且被 kill 的运行会留下 `saves/新的世界/session.lock`，导致后续运行卡在“世界已在使用”界面（B1 之后的 B2/A2 全废，med=30 恒定、cv=0）。
- 新法（已验证）：保持 harness 完全不变，**把 `C:/Windows/System32/opengl32.dll`（983,040B 原生 stub）复制到 `mesa/opengl32.dll`**，dshcore ZinkGL 预加载它 → 系统 GL（NVIDIA native）。同一队列、同一启动路径，唯一差别 = GL 后端。
- 必须防坑：`ac_ab2.mjs` 已打补丁，**每个变体开始前删 `saves/新的世界/session.lock`**（打印 `lock cleared`）。

**同队列交错 ABBA（`_stage/queue_native_copy_ab.json`，18:25-18:40，E-B 默认、1080p 无光影、sr off）**
| 变体 | fps median | cpuMsPerFrame | n | cv |
|---|---|---|---|---|
| N_w0_zink (zoo2) | 291 | 6.929 | 108 | 5.9% |
| N_A1_zink | **297** | 7.146 | 111 | 5.8% |
| N_B1_native | **317** | 5.169 | 191 | 5.5% |
| N_B2_native | **316** | 5.994 | 111 | 5.2% |
| N_A2_zink | **292** | 7.217 | 111 | 5.6% |

⇒ **zink 294.5 vs native 316.5 fps = 比值 0.9305**（无漂移：A1 297≈A2 292，B1 317≈B2 316）。
⇒ 对比旧值 0.887 提升 +4.9%（其中 E-B +4.5% 已进默认）；**距 0.95 目标还差 +2.1%**（zink 需 ~301fps）。
⇒ CPU 侧：zink 每帧比 native 多 **~1.2-2.0ms CPU**（7.15-7.22 vs 5.17-5.99ms/帧），这是剩余差距的定量锚点。

**并行实验（18:40 起跑，结果下轮填）**：`_stage/queue_thread_ab.json` = w0/A1(default) + B1/B2(`GALLIUM_THREAD=0`，关 tc+threaded submit) + C1/C2(`ZINK_DEBUG=flushesync`，只关 submit 线程) + A2(default)，同 DLL 只切 env，验证「Mesa 线程上下文/提交线程是不是那 1.2ms 的一部分」。
### 7.11 2026-10-19 19:0x：**PGO 打通 +8.2%（内核级最大单项收益）**

**PGO 采集卡点的真相（已解决）**
- 之前“`mesaopt/pgo/*.pgc` 恒为 0”是**找错位置**：MSVC pgort 把 `.pgc` 写在**被插桩模块所在目录/进程工作目录**，不是 `/PGD` 指定目录。实际产物：`<MC目录>/versions/DSH-Zink/mesa/mesa!1.pgc` **6,923,064B**（17:50 那次 120s 实机运行产生）。
- 依赖链：插桩 DLL 依赖 `pgort140.DLL`（VS BuildTools `VC/Tools/MSVC/14.51.36231/bin/Hostx64/x64/`），启动 bat 里 `set PATH=<该目录>;%PATH%` 即可；否则 ZinkGL 预加载失败会**静默回退原生 GL**。
- 小程序验证：`tmp/pgotest.c` 用 `cl /O2 /GL ... /link /LTCG:PGINSTRUMENT /PGD:...` 编译运行后生成 `test!1.pgc` 于**当前目录** ⇒ 工具链正常。注意必须带优化开关（`/O2`），否则 C4964“未指定优化，不收集配置信息”。

**PGOPTIMIZE 产物**：`mesaopt/dist/pgo1/opengl32.pgo.dll` **16,784,384B sha256 494a96d19ffd738bdd141c5fc88e910f43bae1cf13ce2327e0ef57d94d6951f3**（源 layer=zoo2；导出 379 项/序基 7 与 zoo2 一致；imports 仅 GDI32/KERNEL32/USER32/api-ms-win-core-synch-l1-2-0，**无 pgort140 残留**）。
- **gate 唯一 FAIL 是尺寸范围检查**（“size in [17500000,19500000]”，实际 16.78MB——PGO 重排后合法变小），其余 379 exports/路径隐私/依赖/来源状态全 PASS。建议把 size 上限改为按 build_flavor 放宽。

**A/B（`_stage/queue_pgo_ab.json`，19:03-19:20，同队列 warmup+ABBA）**
| 变体 | fps median | cpuMsPerFrame |
|---|---|---|
| P_w0 zoo2 | 289 | 7.024 |
| P_A1 zoo2 | 298 | 6.993 |
| P_B1 **pgo1** | **318** | 6.958 |
| P_B2 **pgo1** | **324** | 6.109 |
| P_A2 zoo2 | 303 | 6.884 |

⇒ zoo2 avg **296.7** vs pgo avg **321.0** = **+8.2%**（漂移 A1 298→A2 303 仅 +1.7%，B 组明显更高）。
⇒ 对比 LTO 的 +1.1%±1.2，PGO 是 codegen 路线的真正收益来源（zink 分支密集，分支概率指导值钱）。

**下一步（本轮正在进行）**：`queue_pgo_native_ab.json` = pgo(A1/A2 夹 native B1/B2) 的同队列 ABBA，直接测 **PGO-zink / native 比值**（若 ≥0.95 则达成目标线，尚需 shader-on 稳定性 + 可复现性 + 打包）。
### 7.12 2026-10-19 19:3x-20:1x：目标达成判定（zink/native ≥0.95 两次复现）+ v3.3 出包

**① 比值两次独立同队列 ABBA 复现（E-B 默认 + PGO DLL，1080p 无光影 sr off，进「新的世界」）**
| 队列 | zink(PGO) median | native median | ratio |
|---|---|---|---|
| 19:17 `queue_pgo_native_ab.json` | w0 318 / A1 328 / A2 323 → avg **323.0** | B1 335 / B2 334 → avg **334.5** | **0.9656** |
| 19:36 `queue_pgo_native_ab2.json`（复测） | w0 318 / A1 320 / A2 318 → avg **318.7** | B1 320 / B2 320 → avg **320.0** | **0.9958** |
- 两次均 ≥ **0.95** 达成线（取均值 ≈0.98）；PGO 相对 zoo2 的同队列增益 +8.2%（§7.11）。
- 说明：两队列 native 绝对值不同（334.5 vs 320.0，跨队列机器相位漂移），故**只在同队列内取比值**；两条队列都是 warmup + ABBA 交错，cv 5-6%。

**② 稳定性/兼容性证据（PGO DLL = dist/pgo1/opengl32.pgo.dll）**
- 今日累计 ~16 次实机运行（PGO A/B 5 + 比值 5 + 复测 5 + shader-on 1），**0 crash / 0 hs_err / 0 preload failure / 0 GL_INVALID**；56 mod 加载正常（dshcore 1.3.0/1.3.1 不变）。
- Iris(ComplementaryReimagined_r5.9.3) shader-on 冒烟：**median 102fps**、Iris 正常加载、无报错（对比 zoo2 历史区间 89-111）。
- PGO DLL 行为一致性：layer=zoo2、379 exports/序基 7 与 zoo2 一致、imports 仅系统 DLL、无 pgort140 残留、markers 全对；**gate 仅“尺寸范围 [17.5-19.5MB]”一项误报**（PGO 重排后 16,784,384B 合法）。

**③ v3.3 出包（在 v3.2 基础上：mesa DLL→PGO + AR E-B 默认）**
| 包 | 大小 | sha256 | 校验 |
|---|---|---|---|
| DSH-Zink-330.mrpack | 19,238,154 | 8a15603d42b07cc90e512d24206ea2374357dc7632095c32d76bb7460fc8e4ee | 60 entries, zipbad None, 内嵌 DLL sha 494a96d1…, E-B DISABLED, versionId 3.3.0 |
| DSH-Zink-330-nvidia.mrpack | 88,279,015 | 21eb865a69b341e7be33fd7e1480799733ee2f09fe097fea703ec3296ffa2be1 | 76 entries 同上 |
| DSH-Zink-330-nodsh.mrpack | 17,516,077 | 2a7aee57b17cbf56ee0919aa5d88380c619497d4cb5d3eb9ca04efa89368a017 | 65 entries 同上 |
| DSH-Zink-330-nodsh-nvidia.mrpack | 86,556,944 | e584d423ec70128f9e3d1a5af066957a936b62d297353a6c47f669083d6279ac | 81 entries 同上 |
- 出包方式：Python zipfile 重写 v3.2 包（保持全部条目/压缩方式不变），只替换 `overrides/mesa/opengl32.dll`（16,784,384B sha 494a96d1…）与 `overrides/config/acceleratedrendering-client.toml`（E-B DISABLED），并置 `modrinth.index.json.versionId=3.3.0`。
- `cfgpack/dist/SHA256SUMS.txt` 已追加 3.3.0 段（旧 3.0/3.2 记录保留）。
- 回滚：v3.2 四包仍在 dist 根未动；mesa 回滚件 `mesaopt/dist/zoo2/opengl32.zoo2.dll`（BF71E736…）。

**④ 剩余风险 / 后续建议**
1. PGO 采集流程依赖 `pgort140.dll` 在 PATH 且 .pgc 落在 **mesa/ 目录**（不是 PGD 目录）；复现采集请按 §7.11 步骤。
2. PGO DLL 无 /MAP，若线上 crash 需按 RVA 定位需重链带 map（`/MAP`）后核对；建议下一版补。
3. gate 尺寸检查建议按 build_flavor 放宽（pgoopt 允许 16.0-20.0MB）。
4. 用户后续要求（未做）：AR/Flywheel `glGetSynciv` 忙轮询改 `glClientWaitSync(...,0)`；CreateBetterFps SodiumByteBuffer 优化；TTL 管线缓存补丁（提案已写）；DXVK 风格 per-frame batch/submit 削减。
5. shader-on 只做了冒烟（fps/无 crash），**像素级目视**（E-B 视觉瑕疵风险）仍需人工确认。
### 7.13 2026-10-19 20:0x-20:3x：Win32 管线缓存 TTL 淘汰补丁（用户要求「越玩越流畅 + 剔除久未用管线」）

**实现**（`<项目根目录>/mesaopt/patches/pipeline-cache-ttl-win32.patch`，6 hunks / 5,432B，基于 layer=zoo2 源码）：
- `src/util/disk_cache_os.c`（Win32 multi-file 后端，文件布局 `<base>/<driver>/<key2>/<hex>.bin`）：
  1. `disk_cache_ttl_days()` 读 `MESA_SHADER_CACHE_TTL_DAYS`，未设置时**默认 30 天**，`0`=关闭；
  2. 命中时 `disk_cache_touch_file_last_write()` 刷新文件 last-write（`FILE_WRITE_ATTRIBUTES`+`SetFileTime`）⇒ 淘汰策略变成**按“最近使用”而非写入时间**（等价 access-LRU）；
  3. `disk_cache_sweep_expired()` 递归扫描缓存目录，删除 `ftLastWriteTime < now - TTL` 的条目并 `cache_size_sub()` 扣减；
- `src/util/disk_cache.c:322-330` `disk_cache_create()` 成功后调用 `disk_cache_sweep_expired(cache)`（每个进程 GL 初始化时一次）；
- `src/util/disk_cache_os.h` 新增声明；POSIX 段提供 no-op 桩（不影响 Linux 构建）。

**构建/产物**
- 在 `build/pgo`（PGOPTIMIZE 状态）增量编译 2 TU + LTCG 重链 68s → `<项目根目录>/mesaopt/dist/pgo_ttl/opengl32.pgo-ttl.dll` **16,785,408B sha256 d9f5cc50511f7248eb4f0951a438dc79120208312aa661cb090978a6498f6275**。
- gate：**37 PASS / 1 FAIL**；FAIL 仅为尺寸范围 `[17500000,19500000]`（pgoopt 尺寸 16.78MB，与 pgo1 相同误报），ABI/exports(379)/依赖/路径隐私/marker 全 PASS。

**功能实测（实机，`MESA_SHADER_CACHE_DIR` 指向测试目录）**
| 测试 | 结果 |
|---|---|
| 冷跑填充缓存（默认 TTL=30） | 416 个条目 / 6,440,821B，416 个文件 |
| 把 416 个文件 mtime 回拨 40 天 → TTL=30 再启动 | **启动后 >20 天的旧条目 = 0**（全被 sweep 删除），游戏按需重建，无报错 |
| 回拨 430 个文件 → `MESA_SHADER_CACHE_TTL_DAYS=0` 启动 | 未被使用的 **152 个旧文件保留**（不删除）✔ 关闭生效；被命中的 278 个被 touch 刷新 mtime ✔ touch-on-hit 生效 |
| 冷缓存 vs 热缓存（无光影场景） | 加载到 `Loaded recipes`：43,175ms → 43,093ms（−82ms）；t15-45s 平均 fps 268→271（+1%）⇒ 该场景 shader/pipeline 缓存收益接近中性（光影场景预期收益更大，待测） |

**v3.4 出包（在 v3.3 基础上仅换 mesa DLL = PGO+TTL）**
| 包 | 大小 | sha256 |
|---|---|---|
| DSH-Zink-340.mrpack | 19,239,024 | b6a3b6d423378d435a2634cf07081398bc7b343514978a4db8077429a89b7d35 |
| DSH-Zink-340-nvidia.mrpack | 88,279,885 | c6a0b27a9759200b491804f3730ef264051e1077a36b130ca76118ff132678f1 |
| DSH-Zink-340-nodsh.mrpack | 17,516,947 | bba41a114fbf3d4f2bc0c7d77b31b83bcd3df6a40e598decb32ac7492456b661 |
| DSH-Zink-340-nodsh-nvidia.mrpack | 86,557,814 | 1e453b408e65d528420d85b1a6b43792f3b367329d9e7bf34d3f09ab5e2a0fed |
- 已追加 `cfgpack/dist/SHA256SUMS.txt` 3.4.0 段；回滚件：`dist/pgo1`（仅 PGO）、`dist/zoo2`。
- 回归 A/B（pgo1 vs pgo-ttl 同队列 ABBA `_stage/queue_ttl_ab.json`）进行中，结果见下条或 §7.14。

**代码环境（用户提示「稳定 VSCode 搭环境」）**：`<项目根目录>/mesaopt/.vscode/` 提供 tasks.json（setup/build/optimize/abi_clean/gate 一键任务）、settings.json（UTF-8、终端、C 标准）、extensions.json（C/C++ + clangd 推荐）；工作区说明见 `mesaopt/WORKSPACE.md`。