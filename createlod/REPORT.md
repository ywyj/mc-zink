# createlod — Task-7 机械动力渲染 LOD（Create 机壳包裹剔除）

> 状态：**交付完成 v1.0**（静态证据 + 可构建 mod + 离线压测场景 + BENCH-REQUEST）；动态 fps 数据待 Lead 跑 A/B 回填（本机禁止启动游戏/bench）。
> 2026-10-19 03:20 电源事故（Lead m00602）：本机切电池供电、GPU 30fps 硬上限 ⇒ **BENCH 冻结至 AC 恢复**；本交付**无任何实测 fps**（从未运行 bench），不存在被污染数据；恢复入口见 [STATUS-createlod.md](STATUS-createlod.md)。
> Owner: createlod / team task-7。算法权威文档：[ALGORITHM.md](ALGORITHM.md)（19,121B）。基准请求：[bench/BENCH-REQUEST.md](bench/BENCH-REQUEST.md)。
> 所有产物在 `<项目根目录>/createlod/` 下；本文件未引用任何实例内文件修改（合规声明见 §11）。

## 0. 结论先行（TL;DR）

1. **已交付可构建 client-only mixin mod**：`mod/build/libs/createlod-1.0.0.jar`（24,622B，gradle 9.5.0 + NeoForge moddev 2.0.141 + NeoForge 21.1.229 构建，`BUILD SUCCESSFUL in 23s`，日志见 `evidence/build-log.txt`）。不改 Create/Flywheel 源码、不存档、删 jar 即回滚。
2. **机制（真零绘制）**：Flywheel 的 `BlockEntityStorage.createRaw` `HEAD` 返回 `null` ⇒ 该 BE 不创建任何 visual/instance；Create 自己的 BER 在可视化开启时本就跳过（`KineticBlockEntityRenderer.java:39` 铁证）⇒ 不是退回慢速 BER，而是完全零绘制。Flywheel 对 null 全程判空安全（`Storage.add/remove/recreateAll` + `VisualizationManagerImpl.visualAtPos`）。
3. **压测场景已离线生成（不启动游戏）**：`bench/datapack/dsh_createlod_bench`，合计 **10,683 件机械方块**，其中 **8,998 件（84.2%）** 在默认 L1/L2/L4 规则下零视觉差异剔除（L1 4,000 + L2 2,352 + L4 2,646），controls 5,685 件保留作诚实基线/视觉对照。
4. **零差异边界已给出**（取证见 §4）：L1 机壳传动杆=封闭盒+内部贴图 0 透明像素 → 永远剔除；L2 小机壳齿轮=贴图 12×4 透明窗被 4 面不透明方块全盖才剔；L4=模型 AABB 的 6/26 邻壳全 opaque 才剔；**大机壳齿轮（内部轮盘 1.875 格 > 机壳 1³ 中段全开）不在 L2/L3 默认名单**（诚实保守，避免可见差异）；L3 远景 32 格是用户明确授权的 LOD。
5. **待 Lead 回填的动态证据**（本机禁止启动游戏）：off/on/far 三变体 fps/GPU/CPU、三机位截图像素 diff、mixin 运行期注入是否成功、Iris shadow pass 是否也画 Flywheel 实例（与 shaders 组 P6 交叉验证）。

## 1. 交付物清单（<项目根目录>/createlod/）

| 路径 | 大小 | 用途 |
|---|---|---|
| `mod/build/libs/createlod-1.0.0.jar` | 24,622B | **交付 jar**（Lead 装入实例） |
| `mod/build/libs/createlod-1.0.0-sources.jar` | 16,337B | 源码 jar（可审计） |
| `mod/src/main/java/com/dsh/createlod/` | 7 个 .java | CreateLod 2481B / config/LodConfig 5874B / mixin/BlockEntityStorageMixin 3049B / policy/ModelBounds 7336B / policy/Occluders 1604B / policy/PolicyEngine 10209B / scan/ScanController 9385B |
| `mod/build-log.txt`、`mod/build.bat`、`mod/build.gradle.kts`、`mod/libs/flywheel-neoforge-1.21.1-1.0.6.jar` | — | 可复现构建（compileOnly Flywheel） |
| `ALGORITHM.md` | 19,121B | 算法定稿：定理/AABB 表/6 vs 26 壳/规则/接入点/缓存/兼容/性能模型/风险 |
| `bench/datapack/dsh_createlod_bench/` | 13 个函数 942KB | 10,683 件离线压测场景（setblock 函数，无游戏依赖） |
| `bench/scene-manifest.json` | 359B | 精确计数（见 §5） |
| `bench/README.md` | 7,496B | 场景安装/机位/截图协议/A-B 流程/判据 |
| `bench/BENCH-REQUEST.md` | — | **Lead 执行清单**（前置→变体→命令→判据→回填） |
| `bench/config/createlod-{off,on,far}.json` | 1,250B×3 | A/B 三份配置 |
| `bench/queue/{off,on,far}.json` + `bench/createlod_ab_queue.json` | 393–1,073B | benchrun3 队列（绝对路径） |
| `evidence/` | 5 + README 4,739B | build-log 991B / jar-listing 4,998B / model-bounds.csv 1,751B / texture-alpha 808B / source-excerpts 18,791B |
| `work/gen_scene.py`、`work/gen_evidence.py`、`work/*.py` | — | 场景与证据生成器（python -X utf8 可复跑） |

## 2. 硬需求映射（用户 m00409）

| 用户要求 | 实现 | 证据 |
|---|---|---|
| 1 万个齿轮/传动杆/水车/发动机，开光影要百帧 | 场景 10,683 件；默认规则零差异剔除 8,998 件 Flywheel visual（顶点+draw 归零，主 pass 少一份；若 shadow pass 也画实例则双份） | §5 场景 + BENCH 判据 on/off ≥30% / 目标 ≥100fps |
| 被黄铜/安山机壳套住 → 降低渲染分辨率 | L3 `encasedFar`：同样 4 方块、相机距离 >32 格剔除（滞回 0.85，默认开）；L2 窗全盖时零差异剔除 | §4.3 窗口几何/纹理；BENCH 三机位在 32 格内故仍要求像素一致 |
| 完全包裹看不见 → 几乎不渲染，但机械逻辑照常工作 | L1/L4 只删**视觉实例**；KineticBlockEntity 的 tick/转速/网络/NBT 完全未触碰（mod 不 mixin 任何逻辑类） | §3 AP1/AP2 + §4.2；BENCH 判据 ctl 动力链持续转 |
| 视觉从外部看不能有差异 | L1/L2/L4 静态零差异证明；L3 仅 32 格外远景；大机壳齿轮不参与；相机安全阀 | §4 + BENCH 三机位逐像素对比 |

## 3. 实现（mod 结构）

### 3.1 AP1 — Flywheel 实例创建拦截（唯一 mixin）
- 文件：`mod/src/main/java/com/dsh/createlod/mixin/BlockEntityStorageMixin.java`（3,049B）
- `\@Pseudo @Mixin(targets="dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage", remap=false)`
- `\@Inject(method="createRaw(Ldev/engine_room/flywheel/api/visualization/VisualizationContext;Lnet/minecraft/world/level/block/entity/BlockEntity;F)Ldev/engine_room/flywheel/api/visual/BlockEntityVisual;", at=HEAD, cancellable=true, require=0, remap=false)`
- 命中 `PolicyEngine.shouldCull(be, cameraPos, wasMarked)` → `cir.setReturnValue(null)`；`catch (Throwable)` → 一次性 disable 并记日志（绝不崩游戏）。
- `require=0` + mixin config `required:false` + 类/方法不存在时静默跳过 ⇒ 别人更新 Flywheel 也不会 crash（兼容硬要求）。

### 3.2 AP2 — 客户端扫描器（状态翻转）
- 文件：`scan/ScanController.java`（9,385B），NeoForge `ClientTickEvent.Post`，全回调 try/catch。
- 每 `rescanTicks=10` tick：以相机所在 chunk 为中心、半径 `min(32, effectiveRenderDistance+2)` chunk 遍历 `chunk.getBlockEntities().values()`；策略翻转时 `VisualizationManager.get(level).blockEntities().queueRemove(be)`（降级）/ `queueAdd(be)`（恢复）。
- `MARKED`（`Long2ByteOpenHashMap`，只存 long 不引用 BE，>200k 清空）用于防抖 + 让 createRaw 知道"本来就被剔"；不依赖事件失效，每次重算（6–26 次 getBlockState）。
- 统计：每 40 tick 写 `config/createlod-stats.txt`（`enabled/disabled/disabled_reason/scans/scan_ms_avg/culled_total/restored_total/marked_culled_positions/flywheel_visuals/rules`），BENCH 直接解析。
- 世界切换/退出 `level` 时 `clearMarks()`。

### 3.3 规则（PolicyEngine.java 10,209B + ModelBounds.java 7,336B + Occluders.java 1,604B）
| 规则 | 触发条件 | 类别 |
|---|---|---|
| L1 `selfCasing` | block ∈ {create:andesite_encased_shaft, create:brass_encased_shaft} | 零差异，永远剔除 |
| L2 `windowCovered` | 小机壳齿轮 + `box.unitScaled()` + 4 个非 axis 邻居 opaque + top/bottom_shaft 端孔被盖 | 零差异 |
| L3 `encasedFar` | 同 L2 方块集 + 距离 > `encasedLodDistance=32`（滞回 0.85） | 用户授权远景 LOD |
| L4 `buried` | block ∈ `buriedBlocks`（20 项）+ 模型 AABB 壳（UNIT 6 邻居 / 大 box 26 邻居）全 opaque | 零差异 |
| L5 `farLod` | 默认 0=关；`farLodDistance` + `farLodNamespaces`（默认 create） | 压测上限旋钮 |

`opaque()` = `!isAir && getRenderShape()==MODEL && canOcclude() && isSolidRender(level,pos)`（保守：判否只是少剔）；相机 AABB 与模型 AABB 相交则不剔。

## 4. 零视觉差异论证（核心证据）

### 4.1 “不建 visual = 真零绘制”链
- Create 6.0.10 `content/kinetics/base/KineticBlockEntityRenderer.java:38-44`：`renderSafe` 第一行（:39）`if (!VisualizationManager.supportsVisualization(be.getLevel())) { ... }` ⇒ Flywheel 可视化开启后 Create 自己的 BER 全部不绘制。
- Flywheel 1.0.6 `impl/visualization/storage/Storage.java`：`add` :127-136 `if (visual != null)` 才 setup+put；`remove` :138-167 `if (visual != null)` 才 delete；`recreateAll` :183-189 `replaceAll` 返回 null 即删 entry；`VisualizationManagerImpl.java:189-190` `visualAtPos` 判空。⇒ createRaw 返回 null 完全安全。
- 因此 L1/L2/L4 命中时：不建实例、不更新、不画 = 零绘制成本。

### 4.2 L1 机壳传动杆 = 封闭盒（永久剔除铁证）
- 模型 `assets/create/models/block/encased_shaft/block.json`：Bottom [0,0,0]-[16,16,2] 六面、Top [0,0,14]-[16,16,16] 六面、SideWest/East、Core [1,0.95,2]-[15,15.05,14] 仅 up/down 面（texture `#1=#opening`）。
- 纹理 PIL 统计：`gearbox.png` / `brass_gearbox.png` 16×16 **0 透明像素**（全 opaque）⇒ 内部旋转 SHAFT_HALF 从外部任何角度不可见。

### 4.3 L2 小机壳齿轮 = 有窗（必须窗全盖）
- `encased_cogwheel/block.json` 中段 [0,6,0]-[16,10,16] 只有 4 个侧向面；侧贴图 `andesite_encased_cogwheel_side.png` 16×16 **48/256 透明像素 = 12×4 窗**（brass 同构；`side_connected.png` 32×32 224 透明像素）。
- 窗口条件：4 个垂直 AXIS 面被不透明方块全盖 + `top_shaft/bottom_shaft` 端孔被盖（属性由 `EncasedCogwheelBlock.java:53-54` 定义，反射读取，零 Create 依赖）⇒ 内部齿轮不可见。

### 4.4 诚实边界（不做的部分）
- **大机壳齿轮**：方块模型仅 1³ 且中段完全无侧向面，而内部 SHAFTLESS_LARGE_COGWHEEL 轮盘 1.875 格（AABB -7,5.975,-7 → 23,10.025,23 px）伸出方块 ⇒ **不在 L2/L3 默认名单**，只在被 L4 判定埋入时剔除，避免任何可见差异。
- gearbox / encased_chain_drive：四向可见 / +z 侧无壁 ⇒ 默认不整块剔除（保守）。
- 光影 SSR 反射里被剔物不会出现在反射中（L3 远景下可感知；BENCH 可复测，必要时关 L3）。

## 5. 离线压测场景（bench/）

场景按区放置（坐标见 `bench/README.md`），全部用 `setblock` 生成、悬空 y=110..165，与地形无关：

| 区域 | 件数 | 默认规则 |
|---|---|---|
| L1 机壳传动杆（andesite/brass 交替） | 4,000 | 全部剔除 |
| L2 小机壳齿轮（窗口被石墙全盖，2,352 个 + 1 覆盖块/面） | 2,352 | 全部剔除 |
| L4 埋入件（26 邻格全 opaque 石块内） | 2,646 | 全部剔除 |
| controls（普通齿轮 1,196 / 大齿轮 156 / 大机壳齿轮 100 / 水车 100 / 大水车 20 / 蒸汽机 72 / 飞轮 20 / 马达轴 21） | 5,685 | **不剔除**，视觉对照 + 动力链运转 |
| **合计** | **10,683** | 剔除 **8,998（84.2%）** |

用法（Lead 独占实例操作）：复制 `bench/datapack/dsh_createlod_bench` 到存档 `datapacks/` → `/reload` → `/function dsh_createlod_bench:setup` → `build_all` → `check_scene`（16 条 `CHECK ... OK` + `CHECK-SCENE DONE`）；`clear_all` 重建；`check_scene` 校验关键点位；四个机位函数 `goto_view / view_close_l1 / view_close_wall / view_monolith`。

## 6. BENCH-REQUEST 摘要（详见 bench/BENCH-REQUEST.md）

| 变体 tag | config | 作用 |
|---|---|---|
| `createlod_mech_off` | `createlod-off.json`（enabled=false） | 诚实基线（10,683 件全渲染+全模拟） |
| `createlod_mech_on` | `createlod-on.json`（默认全规则） | 主验收 |
| `createlod_mech_far` | `createlod-far.json`（farLod@48） | 上限旋钮（允许可见差异，仅参照） |

每变体命令：换 config → `node <项目根目录>\_stage\benchrun3.mjs <项目根目录>\createlod\bench\queue\<v>.json <项目根目录>\_stage\bench_results\createlod`。
验收：on/off fps **≥ +30%**，目标 on **≥100fps**（1920×1080、CR 默认包、开光影）；三机位 off/on 截图**逐像素一致**（L1/L2/L4）；ctl 动力链持续转；`marked_culled_positions ≈ 8998`、`scan_ms_avg < 1ms`；无 crash。
需回填：`bench_results/createlod/*.json` 的 avg/median/cv/tail + `perf.gpuAvg/cpuAvg/vram`、三机位像素 diff、`latest.log` 中 `[createlod]` 行与 stats 文件、mixin 注入报错（若有）。

## 7. 与 shaders 组正交关系

- shaders 组 P6 成本模型：CR 默认包下 shadowMapResolution=2048 / shadowDistance=192 / 实体投影开、方块实体投影关；Flywheel 实例**是否进 Iris shadow pass 未知**。若进，则本 mod 剔除同时省主 pass + shadow pass 两份几何/draw，收益上限翻倍；标定方法 = `p6_mech_noshadow vs p6_mech_base` 差值（队列 `<项目根目录>\shaders\ab\ab-p6-mech.json`）。
- 两杠杆正交：先固定 CR 默认包跑 createlod off/on，再在 on 上叠包侧杠杆（CR_mech_lowshadow / CR_mechopt），预期近似相加。已与 shaders 组互相确认（MECH_SCENE_OPT.md §5.1 已写入我的 A/B 顺序与像素判据）。

## 8. 风险 / 回滚

- **本机无实测 fps**（禁止启动游戏/bench）：性能数字全部待 Lead 回填；报告不虚报收益。
- Iris shadow pass 未知（§7）；SSR 反射中不出现被剔物（L3 远景可感知）；相机钻进方块内部时规则剔除但相机 AABB 安全阀只在模型 AABB 内生效。
- 扫描器开销：`scan_ms_avg` 应 <1ms；若 >0.5ms 调 `rescanTicks=20`。
- Create 未来改模型几何：表可能过时 → 保守兜底（判不准就少剔，安全方向）；L1 名单可配置。
- 兼容：`@\@Pseudo`@ + `require=0` + mixin json `required:false` + Flywheel 1.0.x 版本探测 + 全回调 try/catch 自禁用 + `enabled=false` 总开关；删 jar 回滚。
- 与 acceleratedrendering / synaxis / irisveil / createbetterfps 无同点注入冲突（我们只注入 Flywheel BlockEntityStorage，且 require=0）。

## 9. 复现命令

```bat
:: 构建（必须 .bat + cmd /c；pwsh 直跑 gradle 会失败）
C:\WINDOWS\System32\cmd.exe /c <项目根目录>\createlod\mod\build.bat
:: 产物 <项目根目录>\createlod\mod\build\libs\createlod-1.0.0.jar
```

```bat
:: 反编译取证（vineflower 1.10.1 + 实例 java21；先复制 jar 为 ASCII 名）
set JAVA=<MC目录>\versions\DSH-Zink\runtime\java21\bin\java.exe
set VF=<项目根目录>\_tools\gradle-home\caches\modules-2\files-2.1\org.vineflower\vineflower\1.10.1\4f48c5947b21f9ebc743e7c80215ee839d3dc668\vineflower-1.10.1.jar
%JAVA% -Xmx2G -jar %VF% --folder --silent <项目根目录>\createlod\work\create.jar <项目根目录>\createlod\work\decomp\create
```

```bat
:: 场景与证据重生成
cd /d <项目根目录>\createlod
python -X utf8 work\gen_scene.py
python -X utf8 work\gen_evidence.py
```

## 10. 未完成 / 开放项（需 Lead 回填）

1. off/on/far 三变体 fps/GPU/CPU/VRAM（`bench_results/createlod/`）。
2. 三机位截图像素 diff 结果（§6 判据 2）。
3. mixin 运行期注入是否成功（`latest.log` 中 Flywheel mixin apply 行；若失败需按其报错修描述符）。
4. Flywheel 实例是否进 Iris shadow pass（与 shaders P6 交叉）。
5. 真实玩家存档（1 万件非悬空、含光影）下的体感验收 —— 场景为合成基准，不等价真实世界。
6. 若 Create 更新到 6.0.11+/Flywheel 1.1：需复核 createRaw 描述符与模型表（`strictFlywheelVersion` 会先拦）。

## 11. 合规声明

- 我（createlod）**从未修改** `<MC目录>/versions/DSH-Zink` 下任何文件；只做过只读操作（列 mods、读 config、读 jar）。
- 我**从未启动**游戏或 bench；未改过 `config/super_resolution/config.toml`；01:36 的 backend=VULKAN/upscale_algo=dlss 配置漂移与我无关。
- 我的构建/产物全部在 `<项目根目录>/createlod/` 下；一切实例实验走 [BENCH-REQUEST] 交 Lead。

## 12. 电源事故与数据有效性（2026-10-19 03:20，Lead m00602）

- 本机已切电池供电（PowerOnline=False，~1.2h），GPU 恒 P8/210MHz/12W + Battery Boost 类 30fps 硬上限 ⇒ **一切 GPU bench 冻结至 Lead 通知 AC 恢复**；所有 profrun 的 30fps 数据作废。
- 本交付**不含任何实测 fps**：我从未启动游戏/bench，BENCH-REQUEST 的三变体尚未执行，因此不存在被污染的 fps 数据——AC 恢复后可直接从 `bench/queue/` 开跑，无需重测基线。
- 全部产物已落盘并记录 sha256（见 [STATUS-createlod.md](STATUS-createlod.md)）；断电恢复后先按 STATUS 卡校验 jar sha256 再安装。
- 恢复入口：① Lead AC 恢复公告 → ② 装 jar（sha256 b02ad465…）→ ③ 建 DSH-CreateLOD-bench 存档 + datapack → ④ 按 BENCH-REQUEST §3 跑 off/on（时间紧可先只 build_l1 4,000 件）→ ⑤ 回填数据到 §10。

