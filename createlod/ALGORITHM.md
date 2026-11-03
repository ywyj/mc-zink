# ALGORITHM — Create 机壳包裹判定 + Flywheel 实例级 LOD/剔除

模块名: **createlod** (client-only). 目标 MC 1.21.1 / NeoForge 21.1.229 / Create 6.0.10 (Flywheel 1.0.6)。
状态: **v2（2026-10-19）**，与已构建代码一致（mod/src/main/java/com/dsh/createlod/）。
配套: [REPORT.md](REPORT.md)（结论+证据）、mod/（源码+jar）、bench/（压测场景+队列）、evidence/（原始取证）。
v1→v2 更正: 大齿轮几何修正为 1.875 格并移出 L2/L3；壳检查改为实际实现的 26 格 offset 表；扫描器描述与代码对齐；§9 用 10,683 件场景数字。

## 0. 目标（用户 m00409 硬需求 → 可执行判据）
1. 机械逻辑完全不动：只操作 **Flywheel Visual（纯渲染层）**，绝不碰 KineticBlockEntity.tick / 转速 / 网络 / 存档。
2. 被黄铜/安山机壳 **完全包裹**、外部看不见的齿轮/传动杆 → 内部旋转实例整块不创建、不绘制（零视觉差异）。
3. 被机壳套住但露出窗口、或距离很远 → **降 LOD/降分辨率**（用户明确授权："套住→降分辨率；完全看不见→几乎不渲染，但机械逻辑照常"）。
4. 1 万件同场景开光影百帧：GPU 杠杆 = Flywheel 实例数/顶点数；CPU 杠杆 = visual 创建/update 次数。
5. 从外部看不能有视觉差异：L1/L2/L4 规则数学上零差异；L3/L5 只在用户授权窗口内（远景近似）。

## 1. 可见性判据（核心数学）

**定义**：内部几何 = Flywheel 为某 BlockEntity 创建的实例（旋转齿轮/轴/轮盘）。其几何范围用该方块 id 的 **模型 AABB**（局部坐标，离线从 assets 模型 JSON 计算，见 §2）。

**定理（保守充分条件）**：把模型 AABB 外扩 eps(=1e-4) 后取所触及的整数方块格（含跨格），除去方块自身格，剩下若每个格都满足 opaque()，则从这些格之外任何相机位置都看不到内部几何。
- 若 AABB 完全在自身格内 → 需查 6 个面邻居（UNIT 规则）。
- 若 AABB 跨格（大齿轮 1.875 格、水车 3x3）→ 需查 26 个邻居（3x3x3 减自身）。

证明：相机在壳外，任一相机→AABB 内点的线段在进入 AABB 前必先进入壳中某个满不透明方块；该方块朝相机一侧的外表面未被剔除（因为其相邻是空气/非满遮挡方块），线段在进入该方块时已被其外表面截断，故 AABB 内点不可见。若相邻也是不透明块则该面被剔除，但被更外层的面挡住，结论不变。∎

opaque() 谓词（1.21.1 Vanilla API，实现见 policy/Occluders.java）：
```
static boolean opaque(BlockGetter level, BlockPos p) {
    BlockState s = level.getBlockState(p);
    if (s.isAir()) return false;
    if (s.getRenderShape() != RenderShape.MODEL) return false; // 排除 INVISIBLE（屏障/结构空位/光源）
    if (!s.canOcclude()) return false;                         // 玻璃/树叶/台阶/水/含 BE 方块
    return s.isSolidRender(level, p);                          // 遮挡形状是否为满立方体
}
```
- 保守方向：判否只是少剔除，不会造成视觉差异；判是则数学上安全。
- **相机安全阀**：若相机 AABB 与模型 AABB 相交（spectator 钻进方块/贴脸），不剔除（PolicyEngine.shouldCull 首查）。

**两种独立遮挡来源**：
- **自身机壳包裹 (self-casing, L1)**：方块自身 block model 就是封闭盒，内部只有独立 visual —— 不需要邻居条件，永远可剔。铁证：机壳传动杆 encased_shaft 模型封闭 + 内部贴图全 opaque（§2）。
- **邻居/环境包裹 (L2/L4)**：内部 visual 本来可见（机壳窗口），只有邻居或距离条件满足才可剔。

## 2. 模型 AABB 表（离线实测 + 运行时常量）

离线工具: work/gen_evidence.py（读 create.jar 的 blockstates → 递归 parent 链 → element from/to 并集；PIL 统计纹理 alpha），输出 evidence/model-bounds.csv、evidence/texture-alpha.txt。1 格 = 16 模型单位。

实测（px）：
| 模型 | AABB (px) | 说明 |
|---|---|---|
| block/shaft | (6,0,6)-(10,16,10) | 轴 4x16x4 |
| block/cogwheel | (-1,0,-1)-(17,16,17) | 横杠齿轮 1/16 外伸 |
| block/cogwheel_shaftless | (-1,6,-1)-(17,10,17) | **Flywheel 小齿轮视觉** |
| block/large_cogwheel | (-7,0,-7)-(23,16,23) | **1.875 格宽** |
| block/large_cogwheel_shaftless | (-7,5.975,-7)-(23,10.025,23) | **Flywheel 大齿轮视觉** |
| encased_cogwheel/block(_top) | (0,0,0)-(16,16,16) | 机壳 1³ |
| encased_large_cogwheel/block | (0,0,0)-(16,16,16) | 机壳 1³ 但中段全开 |
| encased_shaft/block(_andesite/_brass) | (0,0,0)-(16,16,16) | 封闭盒 |
| gearbox/block | (0,0,0)-(16,16,16) | 四向轴可见 |
| state:creative_motor | (1,0,0)-(15,15,15) | |
| state:steam_engine | (1,0,1)-(15,15,15) | |
| state:turntable | (1,0,1)-(15,7.9,15) | |
| state:mechanical_pump | (2,0,2)-(14,16,14) | |
| state:gantry_shaft | (4,0,4)-(12,16,12) | |
| state:water_wheel | obj loader，JSON 无 elements | **不可从 JSON 取 AABB** |

运行时表（policy/ModelBounds.java，javadoc 记录来源，Map 常量 BY_ID）：
- `UNIT` = (0,0,0)-(1,1,1) → 壳 = 6 面邻居：create 的 shaft,cogwheel,andesite/brass_encased_shaft,metal_girder_encased_shaft,encased_chain_drive,andesite/brass_encased_cogwheel,gearbox,brass_gearbox,clutch,gearshift,creative_motor,encased_fan,gantry_shaft,turntable,mechanical_pump。
- `LARGE_COG` = (-0.5,-0.5,-0.5)-(1.5,1.5,1.5) → 26 邻居：create 的 large_cogwheel,andesite/brass_encased_large_cogwheel。
- `WATER_WHEEL` = (-1,-1,-1)-(2,2,2) → 26 邻居：create 的 water_wheel,large_water_wheel（obj 模型保守按 3³）。
- `BIG` = 同 3³ → 26 邻居：create 的 flywheel,steam_engine。
- 未登记 id → UNIT（保守，且只有 buried 白名单内的 id 才会进规则）。

关键几何结论（决定 L2/L3 边界）：
1. **小机壳齿轮 1³ 机壳 + 视觉 1.0625 格**：视觉只有 1/16 外伸进 x/z 4 个邻格，这 4 格正是 L2 要检查的"垂直 AXIS 面"邻居（满不透明）→ 6 壳充分，L2 零差异成立。
2. **大机壳齿轮不可零差异**：机壳模型仅 1³ 且中段 y6..10 完全无侧向面，而内部轮盘 1.875 格（-7..23 px）伸出方块格 → 轮盘边缘在邻格空气里可见，任何"窗口全盖"判定都不成立 → **encased_large_cogwheel 不在 L2/L3 默认名单**（LodConfig 注释亦写明），只可能在 L4（完全埋入）时剔除。
3. **机壳传动杆封闭盒**：模型 = Bottom/Top 实心板 + 4 侧壁 + Core（仅 up/down 面，texture create:block/gearbox），且 gearbox.png / brass_gearbox.png **0 透明像素** → 内部 SHAFT_HALF 任何角度不可见，L1 零差异。
4. **机壳齿轮中段有窗**：side 贴图 48/256 透明像素，bbox x[2..13] y[6..9]（12x4）→ 4 个垂直 AXIS 面各一窗；端面轴孔由 top_shaft/bottom_shaft 决定（EncasedCogwheelBlock.java:53-54/:139-140）。

## 3. 壳检查算法（实际实现，Occluders + ModelBounds）

```java
// ModelBounds.shellOffsets(Box): 缓存 Map<Box, BlockPos[]>
// 整数足印 = floor(min+EPS) .. floor(max-EPS)（每轴）
// 枚举足印内所有格（除 (0,0,0)），再补齐缺失的 6 面邻居
// UNIT -> 6 个 offset；LARGE_COG / WATER_WHEEL / BIG -> 26 个 offset
public static BlockPos[] shellOffsets(Box box) { ... }

// Occluders.shellOccluded: 全部 offset 都必须 opaque
public static boolean shellOccluded(BlockGetter level, BlockPos origin, ModelBounds.Box box) {
    for (BlockPos off : ModelBounds.shellOffsets(box))
        if (!opaque(level, origin.offset(off))) return false;
    return true;
}
```
- 预计算 offset 表（启动后首次使用），运行时零分配；BlockPos.offset 返回不可变新对象（每格一次，成本可接受）。
- 成本：UNIT 6 次 getBlockState，大 box 26 次。10k BE、rescanTicks=10 → 每 10 tick 6k–26k 次查询（均摊每 tick 600–2600），实测 scan_ms 每次扫描写入 config/createlod-stats.txt；若超 0.5ms 可把 rescanTicks 提到 20。
- 只查被白名单规则选中的 BE（默认 = 位于玩家渲染距离内的 Create 机制块），不是所有方块。

## 4. 判定规则表（PolicyEngine.shouldCull，优先级从上到下，命中即返回）

| 规则 | 命中条件 | 动作 | 视觉差异 |
|---|---|---|---|
| L1 selfCasing | block ∈ selfCasingBlocks（默认 andesite_encased_shaft, brass_encased_shaft）| 不创建 visual / queueRemove | **零**（自身封闭盒，§2.3）|
| L2 windowCovered | block ∈ encasedCogwheelBlocks（默认 andesite/brass_encased_cogwheel）AND box.unitScaled() AND 4 个非 axis 方向邻居全 opaque AND (top_shaft==false OR +axis 邻块 opaque) AND (bottom_shaft==false OR -axis 邻块 opaque) | 同上 | **零**（窗全被盖住，端孔未开或被盖）|
| L3 encasedFar | 同 L2 的 block 集合 AND box.unitScaled() AND dist > encasedLodDistance（默认 32，滞回 0.85）| 同上 | 远景窗口内旋转细节消失（用户授权 LOD），近景不变 |
| L4 buried | block ∈ buriedBlocks AND shellOccluded(level,pos,ModelBounds.get(id)) | 同上 | **零**（视觉 AABB 被不透明壳包住，§1 定理）|
| L5 farLod | farLod 开启 且 farLodDistance>0 且命名空间 ∈ farLodNamespaces（默认 ["create"]）且 dist > farLodDistance | 同上 | 压测上限旋钮，**默认关闭** |

备注：
- 轴与 shaft 属性全部按**属性名反射**读取（PolicyEngine.cogwheelStructurallyCovered：找 name=="axis"/"top_shaft"/"bottom_shaft"），零 Create 编译依赖，Create 改类名不崩。
- L2/L3 的 box.unitScaled() 是硬限制：只有 1³ 方块才可能"窗口全盖零差异"；大机壳齿轮即便被 26 壳埋住也只在 L4 生效。
- L4 的 buriedBlocks 是**白名单**（默认只含 AABB 已核实的 Create 动力件），绝不对未知 mod 方块默认生效；用户可加。
- L3 滞回：dist 进入阈值 d 后要 > 0.85d 才恢复；防相机边缘抖动。
- 优先级按表；相机安全阀优先级最高（在 L1 之前）。
- 所有规则受 config/createlod.json 独立开关控制；enabled=false 完全等价原版。

## 5. Flywheel 接入点（已 javap 验证，Flywheel 1.0.6）

Flywheel jar: mod/libs/flywheel-neoforge-1.21.1-1.0.6.jar (873,570B, 来自 Create 6.0.10 的 jarjar)。

- **AP1（主，创建拦截）** dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage#createRaw
  签名: `protected dev.engine_room.flywheel.api.visual.BlockEntityVisual<?> createRaw(dev.engine_room.flywheel.api.visualization.VisualizationContext, net.minecraft.world.level.block.entity.BlockEntity, float)`
  描述符: `createRaw(Ldev/engine_room/flywheel/api/visualization/VisualizationContext;Lnet/minecraft/world/level/block/entity/BlockEntity;F)Ldev/engine_room/flywheel/api/visual/BlockEntityVisual;`
  mixin: `@Pseudo @Mixin(targets = "dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage", remap = false)` + `@Inject(method = <描述符>, at = @At("HEAD"), cancellable = true, require = 0, remap = false)` → 策略命中 `cir.setReturnValue(null)`。
  null 安全证明（Flywheel 源码，evidence/source-excerpts.txt 有全文节选）：
  - Storage.add（:127-136）: `Visual visual = this.visuals.get(obj); if (visual == null) { visual = createRaw(...); if (visual != null) { setup(...); put(...); } }`
  - Storage.remove（:138-167）: `Visual visual = this.visuals.remove(obj); if (visual != null) { ... visual.delete(); }`
  - Storage.recreateAll（:183-189）: `replaceAll((obj, visual) -> { visual.delete(); Visual out = createRaw(...); if (out != null) setup(out); return out; })` → null 即删 entry
  - VisualizationManagerImpl#visualAtPos（:189-190）: `if (visual != null)`
  - 且 Create 的 BER 已自跳过：KineticBlockEntityRenderer.java:39 `if (!VisualizationManager.supportsVisualization(be.getLevel()))` → **返回 null = 真零绘制，不会退回慢速 BER**。
- **AP2（状态翻转）**: `VisualizationManager.get(level).blockEntities().queueRemove(be) / queueAdd(be)`（VisualManager 仅 visualCount/queueAdd/queueRemove/queueUpdate）。VisualManagerImpl.processQueue 在帧计划内处理 ADD→storage.add、REMOVE→storage.remove。
- **AP3（统计）**: blockEntities().visualCount() 只写日志/统计文件；**不注册游戏内命令**（减少失败面）。
- 不做 AP4（Storage.framePlan / Instancer 级）：L1-L5 在 visual 粒度已覆盖需求，越深越易与 Flywheel 更新冲突。
- 兜底：即使 mixin 未生效（Create/Flywheel 大改导致注入失败），扫描器仍会 queueRemove 已创建的 visual —— 功能降级为"最多晚 10 tick"，不会崩。

## 6. 扫描器（ScanController，实际实现）

- NeoForge `ClientTickEvent.Post`，每 `rescanTicks`（默认 10）执行一次 scan()。
- 不维护 chunk 事件集合：每次扫描以相机所在 chunk 为中心，半径 `radius = scanRadiusChunks>0 ? 该值 : min(32, effectiveRenderDistance+2)`，双重循环取 `level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false)`，`instanceof LevelChunk` 则遍历 `chunk.getBlockEntities().values()`。未加载/未生成 chunk 返回 null 安全跳过。
- 每个 BE：`wasCulled = PolicyEngine.isMarkedCulled(pos)`；`cull = PolicyEngine.shouldCull(be, camera, wasCulled)`；翻转时更新 MARKED + queueRemove/queueAdd + 计数 + 日志（仅 logChanges/debug，前 200 条）。
- createRaw 里独立重跑策略（不依赖扫描器时序）→ 创建当帧即被拦。
- 线程：ClientTickEvent.Post 与 createRaw 都在客户端主线程（渲染线程）→ 无并发问题；MARKED 为普通 Long2ByteOpenHashMap。
- 统计：每 `statsIntervalTicks`（默认 40）写 `config/createlod-stats.txt`，键：enabled/disabled/disabled_reason/rescan_ticks/scan_radius_chunks/scans/scan_ms_last/scan_ms_avg/culled_changed_last/restored_changed_last/culled_total/restored_total/marked_culled_positions/flywheel_visuals/rules。debug=true 时每 100 次扫描打一行日志（含 visualCount）。
- 世界切换/离开：level 引用变化或 mc.level==null 时 clearMarks() + 日志（跨维度/回主菜单不残留）。

## 7. 缓存与失效（明确边界）

1. `MARKED`: Long2ByteOpenHashMap（key=BlockPos.asLong，value=是否已剔除）。只用于防抖 + 让 createRaw 知道"这个位置本来就被剔"，不参与可见性正确性。size>200k 时整体清空（防泄漏）。
2. 世界遮挡：**不做事件驱动失效**。每次 rescan 重算（6–26 次 getBlockState）；方块变化在 ≤10 tick 内被吸收。理由：机壳"套上"是在同一 pos 换 block（EncasedBlock 机制），监听面容易漏；重算更简单更稳。
3. chunk unload：不清 MARKED（只存 long，不引用 chunk/BE）；chunk 重新加载后扫描器重算，Marked=true 且 desired=true 则保持剔除（正确）；若该处方块已被移除，Flywheel 自己 queueRemove，MARKED 残留只浪费 1 字节，无视觉影响。
4. 不持久化、不强引用 BlockEntity；无内存泄漏路径。

## 8. 兼容性设计（Lead m00209 硬要求）

- mixin 注解: `@Pseudo`（目标类缺失不崩）+ `require = 0`（方法不匹配只跳过）+ mixin json `"required": false` + `"injectors": {"defaultRequire": 0}` + `"compatibilityLevel": "JAVA_21"`。
- 版本探测：`ModList.get().getModContainerById("flywheel")` 的 version 以 `1.0.` 开头才启用；不匹配且 strictFlywheelVersion=true → 一次性 log + disabled（不猜测、不崩）。
- 全部回调 try/catch(Throwable)：首次异常 log.error 一次 + `disabled=true` 永久关闭（绝不让异常进 Flywheel 帧循环）。CreateLod 构造同样 try/catch。
- 不改 Create/Flywheel 源码；不用 @Overwrite/@Redirect 改行为；只用 HEAD inject + 纯策略层。
- config/createlod.json 带全部开关；enabled=false 完全等价原版；删 jar 即回滚（无存档/世界数据写入）。
- 其它 mod：acceleratedrendering / synaxis / irisveil / createbetterfps 均不注入 BlockEntityStorage#createRaw（不同方法），HEAD 注入不冲突；若它们把 visual 重新创建 → createRaw 再拦截；queueRemove 幂等。
- 若 Flywheel backend 非 INSTANCING/可视化未启用 → createRaw 不被调用（或 BER 路径生效），自动回到原版，无收益但无风险。

## 9. 性能模型与验收判据

压测场景：bench/datapack/dsh_createlod_bench（离线生成，10,683 件机械方块）：
- L1 机壳传动杆 4000（always cull）、L2 埋墙机壳齿轮 2352（零差异 cull）、L4 石内埋件 2646（零差异 cull）→ **8,998 件在开 LOD 后实例归零**。
- controls（不参与剔除，保证对照诚实）：普通齿轮 1196、大齿轮 156、可见机壳大齿轮 100、水车 100、大水车 20、蒸汽机 72 块、飞轮 20、动力链 21（creative_motor + 20 shaft，可见旋转用于证明逻辑不受影响）。
- 预期：GPU 省 ≈ 8,998 个 Flywheel visual 的顶点/索引与每帧实例上传；CPU 省对应 visual 的 framePlan/update；静态 chunk mesh（机壳方块本体）不变 → 收益上限由原场景 GPU/CPU 瓶颈决定。判据见 bench/BENCH-REQUEST.md：同一存档、同光影、开/关 createlod，fps 提升 ≥30%（目标：开光影 1 万件 ≥100 fps），且固定机位 A/B 截图在 L1/L2/L4 场景**逐像素相同**。
- 队列: bench/createlod_ab_queue.json（benchrun3 格式，绝对路径），变体 = shaders-on off/on/farLod 三组，采样 tail 20 样本均值。
- 静态证据（无本机 bench 权限，本机未启动游戏）: bench/datapack 生成日志 + scene-manifest.json 计数 + evidence/ 全部取证；本机实测由 Lead 执行。

## 10. 风险与边界

1. 光影反射/SSR：被剔除物不在 G-buffer/深度里，屏幕空间反射不回去；非光追光影不适用"隐藏物体出现在反射中"。若发现反射穿帮，关 L3/L5、只留 L1/L2/L4（数学零差异）。
2. **大机壳齿轮不参与 L2/L3**（§2.2）：用户若希望连它一起远景降级，可把 id 加回 encasedCogwheelBlocks，但会看到轮盘边缘消失（非零差异，不推荐）。
3. 相机切入方块（spectator）：相机 AABB 与模型 AABB 相交时不剔除（恢复原视觉）。
4. MARKED 残留：chunk 卸载后保留标记，重新加载时按重算结果刷新，无视觉影响；容量上限 200k 自动清空。
5. 扫描器开销：默认每 10 tick 一次、半径 ≤32 chunk；若 bench 显示 tick 开销 >0.5ms → rescanTicks 提到 20 或 scanRadiusChunks 调小（stats 文件可观测）。
6. 未来 Create 版本改模型几何：AABB 表按版本更新；保守兜底（未登记 id = UNIT）使旧表仍安全（壳更大只会少剔）。
7. mixin 注入失败（Flywheel 大改）：require=0 自动降级，扫描器兜底；若 Flywheel API 变动导致 scan 抛异常 → disabled，回到原版性能。
8. 无本机实测数据：本机禁止启动游戏/bench，本报告性能数字均为设计预期 + Lead 的 A/B 结果回收后更新。
