# HANDOFF — mc-zink 转译性能极致优化（Lead 交接，2026-10-19 23:2x，v3.5.0 已发布）

> 读我：本文件是新会话入口。**本轮（10-19 通宵+上午）已完成「zink 内核级 + 配置级 + 着色器预编译」三条线的实测闭环**，详细结果与数据在 `<项目根目录>/ZINK_OPTIMIZATION_RESULTS.md`（必读，含全部 A/B 数字与判读）。历史在 STATUS.md（§1-§16）与各组 REPORT/STATUS。

## §0 工具环境（先读，避免重踩）

- 嵌套工具正常可用；**上下文要早压缩**（上一会话曾因消息量逼近 1,048,576 上限导致 auto-review 全部拦截）。bench 期间用 compress 折叠长输出。
- 长任务（bench/游戏）必须用 WMI 启动，否则 run_code 退出会连坐杀死子进程：
  ```powershell
  Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = 'C:\Windows\System32\cmd.exe /c "<项目根目录>\_stage\run_xxx.bat"' }
  ```
  但本机实测：`run_code`→`pwsh(run_in_background:true)` 跑 `node benchrun3.mjs` 是稳定的（job 持有），可直接用。
- **PowerShell 坑**：`copy /Y src dst` 是 Copy-Item 别名，`/Y` 非法 → **静默失败**；装 DLL 必须 `Copy-Item -LiteralPath <src> -Destination <dst> -Force`，装完 `Get-FileHash` 复核。
- 环境：node 用 `<用户目录>/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/node/bin/node.exe`；ESM 绝对路径 import 要 `file:///E:/...`；GUI http://127.0.0.1:1838；file policy danger-full-access。
- 测量噪声：单轮 ±5~8%，同队列内可见 +23% 慢热漂移 ⇒ **只能同队列交错锚点 + 多轮**，跨队列绝对值不可比。bench 窗口必须广播「零编译/零大 IO」（teammate 各自是独立 DSH 进程，合计可吃 3-6 核）。
- 风扇/温度：powercfg 锁频会 hang（不要试）；`% Processor Performance` 102~120% 只记录。

## §1 实例当前状态（2026-10-19 12:40 实机）

- 目录 `<MC目录>/versions/mc-zink`，mods 56 jar，存档只有 `saves/新的世界`（超平坦 test world，zinkinfo.cfg join=新的世界）。
- `mesa/opengl32.dll` = **zoo2**（Mesa 26.2.5 + 磁盘 shader cache + P3 补丁），17,421,824 B sha256 `BF71E7369A0142303E7024E364ACD15DFC06960C267C4D537E0ED1F48658597F`（已两轮冒烟：无光影 / Complementary 光影均正常）。
  - 回滚：`Copy-Item <项目根目录>/mesaopt/dist/opengl32.dll → 同路径`（cache-only，sha `C768681A31293E7F600742A70FF2B09C1D039A5DC29364504D6AAE9D15CC3A9F`）。
- mods 关键版本：dshcore-1.3.0（实例里仍是 1.3.0；包内已 1.3.1，二者 classes/mixins 逐字节相同）、super_resolution-0.9.2-alpha.2+dshfix1、Opticores-1.1.0-dshfix、iris-1.8.14-beta.1、sodium-0.8.13、ImmediatelyFast-1.6.15、AcceleratedRendering。
- SR config canonical：backend=OPENGL、upscale=false、ratio=1.5、algo=fsr1、FG OFF、low_latency ON、iris enableShaders=false（bench 默认）。
- **config/immediatelyfast.json `fast_buffer_upload` 当前 = true**（实例保持原样；E-A 只写进 v3.2 包默认）。若要在实例上复验 E-A：改 false 即可，回滚一行。

## §2 本轮已验证结论（勿重做，全部有数据）

### 2.1 最终对比队列（queue_final，9 轮交错，E-A 全局生效）
| 配置 | pooled median | 与 native 比 |
|---|---|---|
| zink cache DLL + E-A | 182 | 0.858 |
| zink zoo1+P3(NO_FB_WAIT) + E-A | 188 | **0.887** |
| native NVIDIA GL + E-A | 212 | 1.0 |
- 分块比值 0.808/0.910/0.854（首块受慢热污染）⇒ 诚实值 **zink ≈ 0.88±0.03 原生 GL**（差 ~12%）。旧 HANDOFF 的 0.63-0.71 是不同测量协议（窗口遮挡/后台限速）产物，作废。
- P3 收益：mix1 +2.0~3.6%；queue_final 相邻配对 +7.7/−2.6/+6.6%（中位 +6.6%），保守 **+2~4%**。

### 2.2 配置级杠杆（无需换 DLL）
- **E-A（已进 v3.2 默认）**：ImmediatelyFast `fast_buffer_upload=false`。机制=true 时每帧 76~81 次 glBufferSubData 走 u_upload staging + vkCmdCopyBuffer；false 走 glBufferData 的 DISCARD_WHOLE_RESOURCE orphan 快路径。实测 P1 drift 校正 +21.5%（受污染夸大）、P1b +4.0%（raw +7.6%）⇒ **保守 +4~8%**；反推 0.73-0.77ms/帧。
- **E-B（暂不进默认）**：AR `[core_settings.block_buffer_binding_restoring].feature_status="DISABLED"`，消除每帧 89 次 glGetIntegeri_v。实测 P1 +12.8%、P1b +4.5% ⇒ **保守 +4~5%**（0.40ms/帧）。风险：跳过状态恢复依赖裸状态，需 shader-on 6 场景截图门禁（`shaders/eopt/SMOKE_SHADERON_CHECKLIST.md`）→ 留 v3.2.1。
- 否决：ZINK_DEBUG=noopt（−18~−27%）、GALLIUM_THREAD=0（−13~−18%）、flushsync（−6~−14%）、DESCRIPTORS=lazy、ZINK_DEBUG=rp、AR debug_context=DISABLED（未定）。

### 2.3 Mesa 内核补丁（自研，源码 <项目根目录>/mesaopt/src/mesa-26.2.5）
- **P3（唯一正收益，已发货）**：删 `zink_flush_frontbuffer()` 里每帧 `util_queue_fence_wait(&bs->flush_completed)`（pristine:1937-1940），≡ 上游 33f0fecc；产物 **zoo2**（无 env 默认开）sha BF71E736…，已纳入 v3.2 包 `overrides/mesa/opengl32.dll`。
- 否决：P1/P4 kopper 同步 present（−1.5~−4%）、P2 在飞 batch 窗口 5→12（无收益）。
- **Mesa 26.3.0-rc1 整树**：构建成功（msvc 兼容修 `shaderapi.c:108` 零长数组→[1]+sentinel），实测 +0~+2.5% 噪声内 ⇒ 不采纳（rc1 的 present 收益已被 P3 覆盖）。
- 待下一轮：zoo3 = zoo2 + 上游 tc 三连（1ca365b2/5710cae1/32658eec，12/12 hunk 已匹配，未编）；Q12 vkCmdUpdateBuffer 快路径（默认 tc 只把 ≤320B 交驱动，Δ≈0）。

### 2.4 诊断插桩（Z0/GLCOST）：已排除，勿再投入
- z0v1/z0only 实机首帧 **原生崩溃**（4 个 hs_err：`EXCEPTION_ACCESS_VIOLATION reading 0x0`，栈 `GL11C.glFinish ← xaero TextureUploader.uploadTextures`）。根因：Z0 补丁在 `tc_fence_finish` 顶部 `screen = zink_screen(ctx->base.screen)`，glFinish 路径 `st_cb_flush.c:80` 传 ctx=NULL → 读 0。修复只需 2 行 guard（`notes/Z0_ROOT_CAUSE.md`），本轮不修。教训：hs_err 在**实例根目录**，不在 crash-reports/。

### 2.5 着色器预编译（A1）：诚实天花板
- 采集器 v0.4.0 已交付（`shaders/prewarm/run/dsh-prewarm.jar` sha AAF69A00…，源采集 441/446≈99%，worklist 208 unique program/367 shader/1.06MB）。
- E1 两轮 warm replay **实为 no-op**（ok=0/fail=208，`NoClassDefFoundError GL20C/GL11C`：agent 在 AppClassLoader，游戏 LWJGL 在 NeoForge LibraryClassLoader）⇒ E1 不能作为 A1 判据；修复方案 v0.4.1（TCCL+MethodHandles 反射），未做。
- 天花板：进世界尖峰 4.6s 里 compile+link 仅 490ms（10.6%），整场 1.17s/7514 帧 ⇒ **A1 最多省 ~0.5s/次进世界，median fps 无感**；主卡顿（4s+）是纹理上传/GL 调用风暴（同帧 20.8 万调用：pixelStorei 115,584 / texParameteri 57,971 / texSubImage2D 28,896）。
- B（VkPipelineCache 落盘）**已实现于 cache DLL**：冷→热 median 140→150（+7%）。

### 2.6 其它已验证（勿重做）
- Vulkan present 无收益且慢 ~9s（默认 OPENGL）；SR FSR1 1.5x 近似中性（opt-in）；purescale FG X2 净亏（默认关）；createlod jar 已构建未实机（`createlod/mod/build/libs/createlod-1.0.0.jar`）；GL_DEDUP v2 只能 apply=0（C7 漏洞：不拦 glBindTextureUnit）；GLCOUNT 全系冻结。

## §3 交付物索引（v3.5.1 已发布；v3.5.0 为上一版）

### 3.1 v3.5.0（2026-10-19 23:2x +08，已 promote 到 dist 根）
| 包 | 大小 (B) | sha256 | entries | files[] |
|---|---|---|---|---|
| mc-zink-350.mrpack | 18,910,166 | `7d153284f3d45b0727037ba8d29ba0a03edbd0801f129176ba6fa4f4ff3721b3` | 60 | 46 |
| mc-zink-350-nvidia.mrpack | 87,565,164 | `f84eb657ad947509931c5cf3fe13208418af5a2510de8bcd2f25dff1e41a8fd5` | 76 | 45 |
| mc-zink-350-nodsh.mrpack | 17,188,559 | `91aca5743fe9e369529bde20b1657d34231319887ab7a70817de64f309ea1324` | 65 | 50 |
| mc-zink-350-nodsh-nvidia.mrpack | 85,843,556 | `0312393cb678f7ebb9ca85f1981ed74be69b8853fbc71106beb7f65af5a85c6c` | 81 | 49 |

- 四包 versionId 统一 **3.5.0**；内嵌 `overrides/mesa/opengl32.dll` 均 pgo2 16,798,208 B sha `31e13c743ae0cc7cd7555ff720d40494b70fb22966faa3e064421295d856c398`；`overrides/config/flywheel-client.toml` 872 B `c67955c7…`（backend=colorwheel:instancing）；`overrides/shaderpacks/ComplementaryReimagined_r5.9.3.zip.txt` 61 B `b4da80c9…`（TAA_DEFINE=0）；AR E-B DISABLED（29,850 B `50efa68d…`）。
- 构建：官方流（build-mrpack.mjs）**--repro** 主套 117/117、nodsh 109/109 通过，两套均“base 一致，nvidia 一致”；随后仅做 versionId 归一化为四包 3.5.0（确定性：两次归一化 sha 相同，工具 tools/_v35_final_normalize.py）。
- 校验：`_check_zip.py` 四包 sums_match=True、testzip=None、roots/deps/versionId 正确；根 SHA256SUMS.txt 追加 v3.5.0 段后 17 行 sha 全部与实际文件一致（all_ok=True），根清单 1,842 B sha `252bdd628ae2d50429311532a93f7be1d63e15a622d4d0b121072f001efcdff6`。
- 与 v3.4 差异：每包仅 4 个 entry 变化（index versionId、flywheel toml、opengl32.dll、TAA txt），无 entry 增删、files[] paths 完全相等（mods 无变化）；见登记表 §3.2 与 cfgpack/REPORT_V35_RELEASE.md。
- 证据：dist 根 `verify-report-350.json`、`verify-report-350-nodsh.json`（官方原始报告，versionId 变体后缀）、`verify-report-350-final.json`（最终四包实测）、`pack-manifest-350*.json`；构建日志 tools/_v35_final_build*.log；diff tools/_v35_release_diff.json。
- 回滚件：v3.4 四包仍在 dist 根未动；`dist/_backup-v340/`（v3.4 四包 + 旧根清单）、`SHA256SUMS-v340-root.txt`；单类回滚见 cfgpack/V35_FINAL_MANIFEST.md §1，整体回滚见 cfgpack/REPORT_V35_RELEASE.md §6。
- 文档 sha256：REPORT_V35_RELEASE.md 见构建时记录；V35_FINAL_MANIFEST.md `0b78c0cd…`；RELEASE_NOTES_V35.md `5039856c…`；REPORT_V35_CHANGES.md `389c30bb…`。

### 3.1b v3.5.1 候选（未发布；等 zsync 实机验证）
- dryrun 目录：dist/_v35.1-dryrun-zsync/（基于 v3.5.0 已发布四包 -SrcTag 350 + dshcore-1.3.1-zsync 替换，未 promote）。
- 包：mc-zink-351.mrpack 18,912,356 B sha `33c924206204be3ffd8a8992c51a8be499441ec35551a227ba7be0720743ac27`；-nvidia 87,567,353 B `d06391a2c0f4b47c7fde65ffcf48f3ad855c30b951ad6dfe30d6781877623e25`；-nodsh 17,188,446 B `ad7a9605c01f5bebc13f835703dfd31c2d8ea96aecc062640d21e653a7998e05`；-nodsh-nvidia 85,843,447 B `7053f61ae35c084e7517b648f328b8052549466f6d96ab5f2d7686cb58ca48d3`。四包 versionId 3.5.1、entries 60/76/65/81 不变、--repro 一致。
- 输入 dshcore zsync jar：dshcore/build/dshcore-1.3.1-zsync.jar 2,550,735 B sha `25798128f00dade093c166c3c15b48a3158890e7410acfcc3b5d087c1505e58c`（R8-1 源码重建，记录见 cfgpack/ZSYNC_MERGE_131.md）。
- nodsh 两包无 dshcore entry → -ReplaceEntry 跳过（不算失败）；若要 nodsh 也带 zsync 需改 5 组成 jar 方案，需 Lead 决策。
- **同时待决策的缺口**：v3.5 四包缺 colorwheel jar 但 flywheel backend=colorwheel:instancing → 新用户可能缺后端；add-override 干跑见 dist/_v35-dryrun-modgap/（entries 61/77/66/82，repro 一致）与 cfgpack/MODS_GAP_ANALYSIS.md。
- 详见 cfgpack/REPORT_V351_DRYRUN.md。发布前提：zsync 实机验证 + colorwheel 是否并入 + Lead promote。

### 3.1c v3.5.1（2026-10-19，已 promote 到 dist 根）
- 四包：mc-zink-351.mrpack 19,375,235 B sha 3eb64d45c85327f2dbe1dd762ad3e772593fd7c3f636192a9986786452213db7；-nvidia 88,030,232 B 05b594c6522bc8ebb5faa4751548282eb13afa9e233503010c7850118747c9ea；-nodsh 17,651,325 B c1b17ca755f932e402ea827bb2efc14144434fdb00a6cc9d7c0367ac4395fe2c；-nodsh-nvidia 86,306,326 B 5f216e93e58a35ce36e64cdc1660e564a6f3a3565eae6e7f8d67413cdec0030a。
- 变更：dshcore-1.3.1.jar → 1.3.1-zsync（2,550,735 B sha 25798128…，仅 base/-nvidia 有该 jar；nodsh 两包无 entry，保持 3.5.1 不变）；四包新增 overrides/mods/colorwheel-neoforge-1.3.0+mc1.21.1.jar（514,376 B sha 3c7c1f91…，修补 colorwheel:instancing 后端缺 jar）。entries 61/77/66/82、versionId 3.5.1、内嵌 DLL pgo2 31e13c74…、E-B DISABLED。
- 根 SHA256SUMS.txt 已追加 3.5.1 段（保留 v3.4/v3.5 段）；回滚件 SHA256SUMS-v350-root.txt；发布流程/自检见 cfgpack/RELEASE_PROCESS_V351.md。
- 依据：dist/_v35.1-dryrun-full/（REPORT_V351_DRYRUN.md 的 3.5.1 dryrun；本小节由 promote-v351.ps1 -Apply 写入）。
- 独立复核（R11，2026-10-20）：根 SHA256SUMS.txt 2,276 B sha 41437c19…；21/21 sha 行逐行通过（8 条含 size 的也通过；文件共 28 行含段标题/空行——与消息中「13 行」不一致，但 sha 与 Lead 值一致，判为描述口径/笔误，无文件问题）；备份 SHA256SUMS-v350-root.txt 1,842 B sha 252bdd62… 正常；351 四包与候选字节一致、内嵌 DLL 31e13c74 / dshcore-zsync 25798128 / colorwheel 3c7c1f91、无 .tmp/半成品；详见 cfgpack/REPORT_V351_RELEASE.md。

### 3.1d v3.6 候选（未发布；DLL 候选全部 BLOCKED，R18 更新）
- **zoo14-23 候选 DLL 全部 BLOCKED**：实机回归（TACZ depot 场景 AR 纹理回读时 Render thread 永久卡死 nglGetTexImage；同世界 pgo2 正常）；v3.5.1 发布用 pgo2（16,798,208 B sha 31e13c74...）无此问题。v3.6 的 DLL 等 mesa-dev clean rebuild + Lead 实机验收后才可出；未验收前保持 pgo2 基线不变。
- AR 配置候选（CANDIDATE，待 A/B；均须走 build-v36.ps1 -ArToml，-ReplaceEntry 对 AR entry 会被 --ar-toml 覆盖）：优先零风险 = [core_settings] cached_image_size 32→128（v3.5.2 dryrun 已备 dist/_v352-dryrun-arcache，4 包 6e5a8087/34609e01/a9f32205/67805c3e...）；下策 = [accelerated_item_rendering] feature_status ENABLED→DISABLED。
- 既有待验项：GL_DEDUP v3.3 待 stats 判 apply；P3 ZINK_TC_FENCE_NO_READY_WAIT 待 ABBA；asyncparticles workaround 待 bisect；zsync 已随 v3.5.1 发布（运行期生效性仍待实机）。
- 详见 cfgpack/V36_SCOPE.md §1/§1b/§1c；**未发布，dist 根不动**。

### 3.2 v3.4.0（2026-10-19 12:18Z，zip 手术：PGO+TTL DLL + E-B 默认，上一版）
| 包 | 大小 | sha256 |
|---|---|---|
| mc-zink-340.mrpack | 19,239,024 | `b6a3b6d423378d435a2634cf07081398bc7b343514978a4db8077429a89b7d35` |
| mc-zink-340-nvidia.mrpack | 88,279,885 | `c6a0b27a9759200b491804f3730ef264051e1077a36b130ca76118ff132678f1` |
| mc-zink-340-nodsh.mrpack | 17,516,947 | `bba41a114fbf3d4f2bc0c7d77b31b83bcd3df6a40e598decb32ac7492456b661` |
| mc-zink-340-nodsh-nvidia.mrpack | 86,557,814 | `1e453b408e65d528420d85b1a6b43792f3b367329d9e7bf34d3f09ab5e2a0fed` |

- 四包内嵌 DLL 16,785,408 B `d9f5cc50…`（pgo_ttl）；AR E-B DISABLED（`50efa68d…`）；dshcore-1.3.1；entries 60/76/65/81；versionId 扁平 3.4.0；第三方验收 cfgpack/REPORT_V34_VERIFY.md（4/4 PASS）。构建 zip 手术 dryrun 见 cfgpack/REPORT_V35_DRYRUN_PGO2.md 等历史文档。

### 3.3 v3.3.0 / v3.2.0（历史）
- v3.3.0（11:50Z，PGO DLL+E-B）：四包 `8a15603d…`/`21eb865a…`/`2a7aee57…`/`e584d423…`，内嵌 DLL `494a96d1…`（pgo old），entries 60/76/65/81。
- v3.2.0（04:46Z，官方流）：四包 `1c48c8b4…`/`f0e28b54…`/`f90d25b2…`/`ec07215a…`，内嵌 zoo2 `bf71e736…`，117/117、109/109；回滚件 SHA256SUMS-v300.txt。
- v3.0.0 回滚件：dist/SHA256SUMS-v300.txt（179 B `9d43f63a…`）。

### 3.4 其它交付物（Mesa / 研究 / 着色器 / bench / 报告）
- Mesa：mesaopt/dist/pgo2/（v3.5.0 发货 DLL `31e13c74…`）、dist/pgo_ttl/（v3.4 DLL `d9f5cc50…`）、dist/zoo2/（v3.2 发货）、dist/opengl32.dll（cache-only 回滚）；文档 mesaopt/notes/{LAYER_BUILDS.md,ZOO2_BUILD.md,Z0_ROOT_CAUSE.md}。
- 研究：zinkperf/ 8 份（PATCH_QUEUE、MESA_LINEAGE、SUBDATA_MECHANISM、RC1_PRESENT_DELTA、ENV_SWITCHES_265、AAA_PLAYBOOK、VK_INVENTORY_NOTES、Q12_UPDATABUF.patch）。
- 着色器：shaders/prewarm/（采集器+预热 agent+worklist+分析链）、shaders/glcount/、shaders/eopt/（E-A/E-B 补丁包+shader-on 冒烟清单）。
- bench 设施：_stage/bench3.mjs、ac_ab2.mjs、ac_dll.mjs、benchrun3.mjs、queue_*.json、an_final.js、clocklog.ps1。
- 出包工具：cfgpack/tools/build-mrpack.mjs（官方流）、build-v35.ps1 + _v35_surgery.py（zip 手术）、_v35_final_normalize.py（versionId 归一化）。

## §4 出包流程与下一轮顺序（reality：v3.5.1 已发布；v3.6 候选准备中）

### 4.1 出包 / 验收流程（v3.5 起实际流程）

**A. 官方流重建（输入快照已修，用于定版复验/自动化）**

    cd /d <项目根目录>\cfgpack
    node tools/build-mrpack.mjs --repro --version=3.5.0 --with-dshcore=<项目根目录>/dshcore/build/dshcore-1.3.1.jar --out=<项目根目录>/cfgpack/dist/_v35-dryrun-snap
    node tools/build-mrpack.mjs --no-dshcore --repro --version=3.5.0-nodsh --out=<项目根目录>/cfgpack/dist/_v35-dryrun-snap-nodsh
    python tools/_check_zip.py <项目根目录>/cfgpack/dist/_v35-dryrun-snap <项目根目录>/cfgpack/dist/_v35-dryrun-snap-nodsh

- 期望：主套 117/117（base 53+nvidia 51+cross 13）、nodsh 109/109；--repro 双构建字节一致；输入快照 DLL 已从 zoo2 修为 pgo_ttl（`d9f5cc50…`）并同步 TAA off 设置，官方包不再静默携带 zoo2/旧 TAA。
- 注意 versionId 口径：官方 buildIndex 写变体后缀（3.5.0/-nvidia/-nodsh/-nodsh-nvidia），v3.4 发货为四包扁平 3.4.0；定版需 Lead 定口径（建议扁平 3.5.0，必要时提升前统一改写）。

**B. 发货包（zip 手术；v3.5 配置类改动用此路径可保持扁平 versionId）**

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/build-v35.ps1 -Dll <项目根目录>/cfgpack/tools/inputs/_backup/mesa-opengl32.pgo_ttl.d9f5cc50.dll -Version 3.5.0 -Out <项目根目录>/cfgpack/dist/_v35-dryrun-config -ReplaceEntry "overrides/config/flywheel-client.toml=<项目根目录>/cfgpack/config/flywheel-client.toml,overrides/shaderpacks/ComplementaryReimagined_r5.9.3.zip.txt=<项目根目录>/cfgpack/tools/inputs/config/ComplementaryReimagined_r5.9.3.zip.txt" -Repro -Force

- 输入：dist 根 v3.4 四包（-SrcTag 340）+ 最终 DLL + canonical E-B AR + flywheel toml + TAA txt；输出 entries 60/76/65/81 不变、扁平 versionId 3.5.0；若最终 DLL 为 pgo3，只需换 -Dll 重跑（约 1 分钟）。
- 输出默认 <SrcDir>/_v<digits>-staging；Out==SrcDir 必须显式 -Force（只有 Lead promote 才写 dist 根）。

**C. promote 顺序**：dryrun → python tools/_check_zip.py <绝对目录> → 内嵌 DLL/配置 sha 独立复核 → Lead 批准 → 写 dist 根（保留 v3.4 回滚件 dist/_backup-v340/）→ 更新 HANDOFF §3/REPORT → 通知 Lead。

### 4.2 当前 reality（2026-10-19 23:2x +08）
- **v3.5.0 已发布**：dist 根新增 350 四包（sha 见 §3.1）；v3.4 四包未删除/未覆盖；根 SHA256SUMS.txt 已追加 v3.5.0 段（1,842 B sha `252bdd62…`），v3.4 段保留。
- 最终 DLL = pgo2（Lead A/B 决定：pgo_ttl 104/112.6 vs pgo2 109.3/113.6，pgo2 不劣；.text 与 pgo3 相同=双 profile，TTL 在）；输入快照已更新（tools/inputs/bin/mesa/opengl32.dll + _snapshot.json）。
- 发货口径：官方流 build-mrpack.mjs 产物（--repro 一致）→ versionId 归一化为四包 3.5.0；entries 60/76/65/81、files 46/45/50/49。
- 已定：TAA_DEFINE=0 随包；flywheel backend=colorwheel:instancing；E-B 沿用 v3.4；R2 官方重建不可复现已修复（输入快照 pgo2 + TAA off）。
- 回滚件：`dist/_backup-v340/`、`SHA256SUMS-v340-root.txt`；单类回滚见 cfgpack/V35_FINAL_MANIFEST.md §1，整体见 cfgpack/REPORT_V35_RELEASE.md §6。
- 剩余门禁：Lead 实机目视（光影动态结构/残影/TAA off 代价）与发布通知。
- v3.5.2 候选（仅 AR [core_settings] cached_image_size 32→128）dryrun 已备：dist/_v352-dryrun-arcache（CANDIDATE-NOT-FOR-RELEASE，4 包 sha 见 §3.1d）；待 Lead A/B 与发布决定。

### 4.3 v3.5 promote 条件（全部满足才写 dist 根）
1. Lead 最终 DLL 定稿（pgo3/pgo_ttl）并写入 V35_FINAL_MANIFEST.md §1.2，dryrun 重跑 verify 全 PASS；
2. versionId 口径拍板并统一四包；
3. TAA off / flywheel backend 实机目视门禁通过（动态结构可见、残影改善、无明显视觉缺陷）；
4. 四包 _check_zip.py + 内嵌 DLL/配置 sha + SHA256SUMS 复核 PASS；
5. Lead 明确 promote 指令。

### 4.4 v3.5 之后待办（按优先级）
1. **鼠标转动掉帧（hitch 型）**定位与修复——v3.5 之后第一优先。
2. 纹理上传风暴（pixelStorei/texParameteri 10 万级/帧）→ Java 侧削减或 IFA/AR 配置。
3. 着色器预热 v0.4.1（TCCL 修复）→ E1b 两轮验证（预期 −0.5s/次进世界）。
4. zoo3（P5 tc 三连）/ zoo4 LTO / glthread 等研究型 DLL：入包需更新输入快照与 manifest，并走官方流或 zip 手术验收。
5. createlod 实机、soak、FSR4/XeSS、Q12b（放大 TC_MAX_SUBDATA_BYTES）。
6. **AR 物品渲染回读（CANDIDATE，未验证）**：AR 的物品渲染对每个新纹理做 glGetTexImage 回读，zink 下可能造成卡顿；候选缓解 = acceleratedrendering-client.toml 的 [accelerated_item_rendering] feature_status 由 ENABLED 改 DISABLED（或 [core_settings] cached_image_size 32 改 128），需 Lead A/B 判据（ABBA >=4 轮、p99/avg 改善且机制指标下降、无视觉/稳定性回归）；路径/回滚/判据见 cfgpack/V36_SCOPE.md §1b。
7. **zoo14-23 候选 DLL 实机回归阻断（R18）**：TACZ depot 场景 AR 纹理回读永久卡死（nglGetTexImage）；v3.6 的 DLL 待 mesa-dev clean rebuild + Lead 实机验收；v3.5.1 的 pgo2 基线（31e13c74...）不受影响。

### 4.5 GPU 低性能异常时的数据有效性规则（2026-10-19 新增）
- **任何 fps 数字必须同时带三要素才有效：loader（native / zink）、powerInfo（功率/时钟/温度/限频或降频标志）、同队列 control 对照（同一轮交错的基准变体）**；缺任一项的数据不得用于 A/B 结论或对外汇报。
- 出现「GPU 低性能异常」（fps 明显低于同配置历史基线、时钟/功率受限、温度异常、驱动降频）时：该轮数据**作废**，不进入统计；先排查功耗/散热/后台负载/harness 状态，再在同一队列内交错重跑，并保留异常轮日志用于归因。
- 禁止跨队列比较绝对值（不同测量协议/窗口/后台状态不可比；沿用 §0 的「只能同队列交错 + 多轮」原则）。
- 记录格式建议：queue_id / 轮次 / 变体名 / loader / median fps / p1 fps / powerInfo / control 变体 median；bench 输出须能按 queue 聚合出 control 对照。

- **电池态与 30fps 上限（Battery Boost，条件触发）**：若 samples 恒 30 需先排查 NVIDIA Battery Boost / 供电态；本机曾在电池态下测得 352fps（未触发 30fps 硬上限），不能仅凭电池态判 INVALID；仍要求 loader/powerInfo/control 三要素（详见 stability/GPU_LOWFPS_NOTE.md 与 stability/REPORT.md §10）。

## §5 用户目标达成度（诚实版，v3.5.1 发布后更新）
- ✅ v3.5.1 已发布（dist 根 351 四包 `3eb64d45/05b594c6/c1b17ca7/5f216e93…`，根 SHA256SUMS 2,276 B sha `41437c19…`；v3.5.0 为上一版）；DLL=pgo2、TAA_DEFINE=0、flywheel backend=colorwheel:instancing、colorwheel override `3c7c1f91…`、dshcore-zsync `25798128…`（base/-nvidia）、E-B DISABLED。
- ✅ 光影下两个核心问题已修：**动态结构不可见**=flywheel backend 配置修复（已入 v3.5 包）；**运动部件残影**=TAA off（已入 v3.5 包，代价天空高频噪声/边缘，FXAA 补偿，可恢复）。
- ✅ 性能：zink ≈ native（TAA on 102.7 vs 101.2；TAA off zink 112.6 / native 127.9）；鼠标转动 moving/still ≈ 1.00（zink）。
- ⏳ **zsync（AR/Flywheel 同步轮询改 glClientWaitSync 非阻塞探测）**：已随 v3.5.1 发布（dshcore-1.3.1-zsync，sha `25798128…`）；运行期是否真正生效仍需实机验证（required=false 静默降级，不能只看游戏没崩）。
- ⏳ **zoo15 插桩（待建）**：Lead 指定的下一阶段插桩工作，尚未开工。
- ⚠️ AR 物品渲染回读（CANDIDATE，未验证）：AR 对每个新纹理 glGetTexImage 回读，zink 下可能卡顿；候选缓解见 cfgpack/V36_SCOPE.md §1b（feature_status ENABLED 改 DISABLED / cached_image_size 32 改 128），待 Lead A/B，未入包。
- ⛔ **zoo14-23 DLL 候选实机回归阻塞（R18）**：TACZ depot AR 纹理回读 Render thread 永久卡死；v3.6 的 DLL 待 mesa-dev clean rebuild + Lead 实机验收；v3.5.1 的 pgo2 基线正常。
- ⏳ **v3.5.2 候选（仅 AR cached_image_size 32→128）**：dryrun 已备 dist/_v352-dryrun-arcache（4 包 6e5a8087/34609e01/a9f32205/67805c3e...），待 Lead A/B 与发布决定。
- ⚠️ 其它未闭环：colorwheel 已随 v3.5.1 补入（overrides-only，3c7c1f91…）、E-B 光影 6 场景目视、A1 真实数字、纹理上传风暴、createlod、soak、FSR4/XeSS；GPU 低性能数据有效性按 §4.5 新规执行。

## §6 用户额外要求「吸收并优化」的 mod 清单（未完成）
- 动态 FPS、移动光源、连锁采集（veinminer 已装）、异步粒子（已装）、资源包加速、鼠标卡顿、CreateBetterFps、实体渲染优化、AR（已装，A/B 噪声内）。
- 要求：直接集成进自研 mod 且 UI/内容与原版一致；机械动力内容显示必须正常。dshcore 目前只合并 zinkgl/zinkinfo/dshopt/dshconfig/createopt。

## §7 v3.5.0 发布完成情况（2026-10-19）
- [x] 最终 DLL 定稿 pgo2（16,798,208 B `31e13c74…`）并更新输入快照
- [x] 官方流 --repro 构建通过（主套 117/117、nodsh 109/109，双构建字节一致）
- [x] versionId 归一化四包 3.5.0（确定性已验证）
- [x] dist 根 promote 四包，v3.4 未删除/未覆盖；根 SHA256SUMS.txt 追加 v3.5.0 段并全量复核 17/17
- [x] _check_zip.py 四包 sums_match=True、testzip=None
- [x] HANDOFF §3/§4.2/§5 更新；REPORT_V35_RELEASE.md 已写
- [ ] Lead 实机目视门禁（光影动态结构/残影/TAA off 代价）与对外发布通知

## §8 风险与回滚（v3.5 更新）
| 风险 | 状态 / 处置 |
|---|---|
| R2 官方重建不可复现 shipped | ✅ 已修：输入快照 DLL→pgo_ttl（`d9f5cc50…`）、shaderpacks .txt→TAA off、_snapshot.json 同步；dryrun --repro 一致 |
| T1 TAA off 视觉代价 | 天空高频噪声 ×4.4、边缘能量 +15%（FXAA 补偿）；需目视门禁；用户可改回 TAA_DEFINE=1 |
| F1 flywheel backend 视觉/兼容 | colorwheel:instancing 修复动态结构，个别光影可能有差异；回滚见 V35_FINAL_MANIFEST §1.3 |
| D1 DLL 未定稿 | 等 Lead pgo3 A/B；定稿后替换 manifest §1.2 并重跑 dryrun |
| V1 versionId 口径不一致 | 官方 rebuild 变体后缀 vs v3.4 扁平；提升前统一（建议扁平 3.5.0） |
| H1 鼠标转动掉帧 | hitch 型未修，v3.5 之后第一优先 |
- 整体回滚：dist/_backup-v340/（v3.4 四包 + 根 SHA256SUMS.txt）；单类回滚见 V35_FINAL_MANIFEST.md §1。
- 脚本级回滚：build-v35.ps1 不带 -ReplaceEntry 即回到 v3.4 内容；官方流回滚重跑 --version=3.4.0 版本。
