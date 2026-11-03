# STATUS — createlod（task-7 机械动力渲染 LOD）

- 更新时间：2026-10-19 03:2x（电源事故期）
- 状态：**task-7 已 complete（rev 3）**；代码 / 场景 / 证据 / BENCH-REQUEST 全部落盘。仅剩 Lead 的 bench 回填。
- 电源事故（Lead m00602）：本机切电池 ~1.2h、GPU 30fps 硬上限 ⇒ 一切 GPU bench/编译冻结至 AC 恢复。本交付**无任何实测 fps**（从未启动游戏/bench），因此不受 30fps 污染、也无需重测。

## 关键结论（恢复后无需重读长文）
- 机制：唯一 mixin = Flywheel `BlockEntityStorage.createRaw` @HEAD 返回 null = **真零绘制**；依据 Create BER 在可视化下不画（`KineticBlockEntityRenderer.java:39`）+ Flywheel 对 null 全程判空安全（`Storage.add:127-136` / `remove:138-167` / `recreateAll:183-189`）；扫描器 queueRemove/queueAdd 翻转；机械 tick/网络/NBT 零触碰。
- 规则：L1 4,000 机壳传动杆（封闭盒 + gearbox.png 0 透明像素 ⇒ 永剔）；L2 2,352 小机壳齿轮（side 贴图 48/256 透明窗，4 垂直面 + 端孔全盖才剔）；L4 2,646 埋入件（6/26 邻壳全 opaque）；L3 >32 格远景 LOD（默认开，用户授权）；L5 默认关。**大机壳齿轮排除 L2/L3**（内部轮盘 1.875 格 > 机壳 1³ 中空，避免可见差异）。
- 场景：10,683 件机械方块，默认规则剔除 8,998 件（84.2%），controls 5,685 件保留作诚实基线。
- 验收判据：off/on fps ≥ +30%、on ≥100fps（1920x1080 CR 默认包）、三机位截图逐像素一致、stats `marked_culled_positions ≈ 8998`、`scan_ms_avg < 1ms`、无 crash。

## 产物 + sha256（恢复时先校验）
| 产物 | 字节 | sha256 |
|---|---|---|
| mod/build/libs/createlod-1.0.0.jar | 24,622 | b02ad4650cffd262b1e3cdd724e59bd0af558477de7056666bd5f81e2ee49baf |
| mod/build/libs/createlod-1.0.0-sources.jar | 16,337 | 2492b477a19541b505fa76621ba5f3cad761e3fcb5b4c5f4c1178263d83b6f5f |
| REPORT.md | 16,975 | c86e99475b5565fb89ec46e92d01fa7de74ce2e39b5b06b16c24062d58de11fb |
| ALGORITHM.md | 19,121 | 663da3735bbccf8aeb87fab52a94e61e6277043cecce8fa4e523cf337417f430 |
| bench/BENCH-REQUEST.md | 6,977 | f3ff434ce66d040d23181eabdf5300ccacc1bd12c11d3058ea72521b80d7c018 |
| bench/README.md | 7,496 | 940242837b07c67c2d3e0bc3b54976370229e3ba29cd2deedbc46fef2c9ae7f1 |
| bench/scene-manifest.json | 359 | 4ad2c2a95a355e85a4c1d98cc765da4047511c7926f0e2663a92c53ceb790bf7 |
| bench/config/createlod-off.json | 1,253 | bb15b919e4a20e64f7dd653a3d4921ba2936000a01fda3d8e7ffc5bd04a41d5d |
| bench/config/createlod-on.json | 1,252 | b3cd7e197eb2ab1bf755211be042583ff77a299df93283ee899ec1986d65cfbc |
| bench/config/createlod-far.json | 1,250 | 25c5561fde85311a748f64e5c32e059cb416922410f33fa331de8fd6e59de959 |
| mod/src/.../mixin/BlockEntityStorageMixin.java | 3,049 | 5920b5f83bc38f617bef88c578a244ebd913978074790f36eb8d6b9971a884f5 |
| mod/src/.../policy/PolicyEngine.java | 10,209 | 24b77b02929ccd961dbd3bf5e89e67234ab88e8f9b68ba662355fcaa88480213 |
| mod/src/.../policy/ModelBounds.java | 7,336 | 2bc2929da355f36bfa87ae989e467042d41c404c96af07b1669d1a0e49764eb8 |
| mod/src/.../scan/ScanController.java | 9,385 | 1ef36f6d2cf5c4015a357adbb73b7325cb4e834f34e00b98e757295137905e12 |
| work/gen_scene.py | 10,160 | a0f4a2ea3577243471e39b7e922f76eb1c6d141060016907919add92740182bb |

## 恢复入口 / 下一步
1. 等 Lead AC 恢复公告（轮询 PowerOnline=True）。
2. Lead 装 jar（sha256 b02ad46… 开头）到实例 mods/（+ 可选 config/createlod.json）。
3. 建独立存档模板 DSH-CreateLOD-bench：复制 `bench/datapack/dsh_createlod_bench` 到 `<存档>/datapacks/`；/reload → `/function dsh_createlod_bench:setup` → `build_all` → `check_scene`（16 条 CHECK OK + DONE）；/time set noon + 锁昼夜/天气；`zinkinfo.cfg join=DSH-CreateLOD-bench`。
4. 按 `bench/BENCH-REQUEST.md` §3 跑 off / on（必要时 far）；每变体前 copy /Y 对应 `bench/config/createlod-*.json` → 实例 `config/createlod.json`。
5. 回填：`bench_results/createlod/*.json` + ALL.json（avg/median/cv/tail/perf）、三机位像素 diff、latest.log 中 `[createlod]` 行 + `config/createlod-stats.txt`、mixin 注入报错（若有）→ 我更新 REPORT §10 并给最终结论。

## 阻塞 / 唯一未知
- 无代码阻塞；本机无实测 fps（禁止启动游戏/bench）。
- 未知 1：mixin 运行期是否注入成功（BENCH latest.log 验证；@Pseudo+require=0 失败也不崩）。
- 未知 2：Iris shadow pass 是否也画 Flywheel 实例（与 shaders P6 `p6_mech_noshadow vs p6_mech_base` 交叉）；若进 shadow pass，收益上限翻倍。
