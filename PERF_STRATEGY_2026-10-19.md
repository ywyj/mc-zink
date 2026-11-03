# PERF_STRATEGY_2026-10-19 — zink 转译开销定位修正与下一步实验

> 生成于 2026-10-19 04:2x（电池 14%，AC 未恢复），基于 G5B + GLCOUNT + ns/call 微基准的一手数据。
> 与 STATUS.md §10–§14、RESUME.md、zinkperf/MESA_CPU_OPT_CANDIDATES.md、shaders/GL_CALL_REDUCTION_PLAN.md 配套。

## 1. 已确定的事实（不要再重复推导）

1. **1080p 关光影、前台、High 优先级、无争用**：zink median 156 / native median 259 → CPU-bound 口径差 0.60（G5B）。每帧 Δ ≈ 3.34ms（2.75ms 额外 CPU + 0.59ms 串行化）。
2. **zink 不发送更多 GL 调用**：GLCOUNT 每帧 total_calls median zink 2141 / native 2134，draws 均 89。⇒ 差距来自「同一调用更贵」，不是「调用更多」。
3. **状态类调用（glBindTexture）在 zink 上不贵**：微基准 N=4000 时 zink 28.2 ns/call、native 38.4 ns/call。zink 只是 batch 录制，真正工作在驱动线程。⇒ **推翻「每次调用 1.2–1.6µs」的均匀假设**。
4. 因此 2.55ms 必然集中在**少数重型调用**上。每帧可疑重型调用量（GLCOUNT 稳态）：
   - glDrawElements 89/帧
   - glBufferSubData 81/帧（其中 **76 来自 ImmediatelyFast**）
   - glTexSubImage2D 7.3/帧均值（尖峰时 43；其中 **16/帧来自原版 LightTexture.updateLightTexture**）
   - glGetIntegeri 89.7/帧（其中 **66.8 来自 AcceleratedRendering SimpleBlockBufferBindingState.record**，同步取值）
   - 合计 ≈ 267 次/帧 ⇒ 若承担 2.55ms 则 **~9.5µs/次**。

## 2. 结论：优化方向要从「削调用数量」改为「削重型调用 / 让重型调用变便宜」

原 GL-DEDUP 路线（uniform 537、texparam 198.7、绑定 193 等）目标是削状态类调用——按实测这些只值 ~30ns/次，即便削掉 1300 次/帧也只值 ~40µs（<2% 帧时间）。**优先级下调**。

新的高价值目标（按预估）：

| 目标 | 每帧次数 | 预估单次成本 | 潜在收益 | 实现形态 |
|---|---|---|---|---|
| glDrawElements | 89 | 高（含驱动录制+提交） | 大批量合并/draw 去重，最有效但最难 | Java 侧或 zink 内部 |
| glBufferSubData | 81（76 IFA） | 中高（映射+拷贝+同步） | **关 ImmediatelyFast fast_buffer_upload 可消 76 次** | 纯配置 A/B，先做 |
| glGetIntegeri（同步读） | 90（67 AcceleratedRendering） | 高（可能触发同步） | 关/替换 AcceleratedRendering 的 binding state 记录 | 配置 A/B 或 mod 补丁 |
| glTexSubImage2D（lightmap） | 16（原版 LightTexture） | 高（每帧整块上传） | 原版 lightmap 增量上传/降频 | 自研 mixin，中等 |
| glFinish/glFlush | ~0 | — | 已无 | — |

## 3. AC 恢复后第一批实验（每条 1–3 分钟，电池也能跑）

按「便宜→贵」排序，全部 1080p / 关光影 / sr:false / 前台 / High：

1. **E-A**：ImmediatelyFast `fast_buffer_upload=false`（config/immediatelyfast.json）→ 期望消 76 次 glBufferSubData/帧，FPS 涨 1–5%。GLCOUNT 复测验证次数。
2. **E-B**：AcceleratedRendering `feature_status=DISABLED`（config/acceleratedrendering-client.toml）或按节关 → 期望消 67 次 glGetIntegeri/帧；风险=实体/物品渲染变慢，需 A/B 双看。
3. **E-C**：微基准 4 个 extraOp（getInteger / bufferSubData / texSubImage2D / drawElements，各 N=4000）→ 直出 µs/次，锁定 2.55ms 的分布。源码已写（GlCountExtra.java，GLCOUNT VERSION 2.2.0），AC 后先跑 `cmd /c <项目根目录>\shaders\glcount\build.bat`。
4. **E-D**：Flwheel backend 回 DEFAULT（已还原）vs flywheel:instancing 的 A/B（instancing 换掉 Flywheel fence 轮询，但 AcceleratedRendering 接手轮询 → 需两轮 GLCOUNT 对比）。
5. **E-E**：Mesa 磁盘 shader cache DLL（mesaopt/dist/opengl32.dll）R1 冒烟 → R2 冷/热启（省启动 100s+，稳态 fps 预期 +0~2%）。
6. **E-F**：purescale X2/X3 4 轮（MFG）；task-9 的「全程 Vulkan present」按 STATUS §14 三选项重新决策。

## 4. 「超过原生 Vulkan」的路径判断（修正版）

- 单纯靠削状态调用**不可能**超过 native GL：天花板是 native GL 本身（两条路径的 GPU 负载相同，zink 只多 CPU）。
- 要「超过」，必须做 native GL 做不到的三件事：
  1. **减少 GPU 工作量**（SR 超分降内部渲染分辨率、光影质量分级、机壳 LOD/整块剔除 → 这些在 native 上同样有效，等于提高上限）；
  2. **减少提交给驱动的工作**（draw 合并、IFA buffer 策略、lightmap 增量上传）；
  3. **把 CPU 工作移到别的线程**（zink 已有驱动线程；Java 侧渲染可异步化的部分）。
- 因此双轨策略保持不变：Mesa/zink 侧削重调用（mesaopt + zinkperf），Java 侧做 GPU 工作量削减与"原生做不到的"优化（createlod、shaders、modcore）。

## 5. 未验证/风险

- ns/call 数据是电池期 30fps 锁频下测的，绝对值可能与 AC 下不同（相对结论应稳定，但需 AC 下复测一次 N=4000 zink/native）。
- glGetIntegeri 是否真的触发同步未直接取证（属假设，需 E-B 的 A/B 数据反推）。
- ImmediatelyFast fast_buffer_upload=false 的收益依赖 IFA 版本实现，需实测。
