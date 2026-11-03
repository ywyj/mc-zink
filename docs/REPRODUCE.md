# 复现指南

本文说明如何在他机上复现 mc-zink 的构建与测量。先说清楚：**能复现的是方法与趋势，不是逐帧数字**。硬件、驱动、mod 版本、世界内容稍有不同，绝对值就会变。

## 一、复现目标

| 能复现 | 不能精确复现 |
|---|---|
| zink 在 MC 1.21.1 上跑起来 | 182 / 212 这类具体帧率 |
| E-A、E-B、P3 的收益方向与量级 | 每一项的精确百分比 |
| 9 轮交错、pooled median 的测量流程 | 当时的室温、背景进程、驱动小版本 |
| 0.85 ~ 0.91 的 zink / 原生比值区间（稳态；首块可低至约 0.81） | 0.858 这个精确值 |
| 已知卡死、黑屏、30 帧锁的排查 | zoo14–23 候选 DLL 的逐字节行为 |

## 二、需自备的材料

| 材料 | 版本 / 说明 | 本仓库是否包含 |
|---|---|---|
| Minecraft Java 版 | 1.21.1 | 否 |
| NeoForge | 21.1.229 | 否 |
| Mesa 源码 | 26.2.5 官方 tarball（约 68,652,756 字节） | 否 |
| 本项目 Mesa 补丁 | `mesaopt/patches/` | **是** |
| `zinkgl` / `zinkinfo` / `createopt` 源码 | 0.4.0 / 0.3.0 / 1.1.0 | **是** |
| `dshcore` 源码 | 1.3.1-zsync 对应源码 | **是**（jar 否） |
| `createlod` / `vkglshim` 源码 | 未实机 / 实验 | **是** |
| 配置模板 | `cfgpack/` | **是** |
| 第三方 mod | 见 2.1，自行获取 | 否 |
| 光影包（可选） | ComplementaryReimagined / photon | 否 |
| NVIDIA 驱动 | 596.21 或相近版本 | 否 |
| 启动器 | 任意支持版本隔离与工作目录设置的启动器 | 否 |
| 1Hz 帧率采样器 | 本项目 `bench3` 未随快照 | 否 |

### 2.1 第三方 mod

本项目实测环境为 56 个 mod。**完整列表未随公开快照**；以下是文档中反复出现、影响结论的核心项：

| mod | 作用 | 获取方式 |
|---|---|---|
| Sodium | 渲染优化，改变区块绘制路径 | Modrinth / CurseForge |
| Iris | 光影加载器 | 同上 |
| Flywheel | Create 的渲染后端 | 同上 |
| Create | 机械动力本体 | 同上 |
| AcceleratedRendering | 渲染加速；E-B 的调整对象 | 同上 |
| ImmediatelyFast | 即时渲染优化；E-A 的调整对象 | 同上 |
| EntityCulling | 实体剔除；`dshcore` 有兼容插件 | 同上 |
| alltheleaks | 内存泄漏修复 | 同上 |
| colorwheel | Flywheel 的 instancing 后端 | 同上 |
| cupboard | 库 mod | 同上 |
| ocuwheel | OC 与 Flywheel 兼容 | 同上 |
| Opticores | 视觉兼容 | 同上 |
| sentrymechanicalarm | 机械臂哨兵 | 同上 |
| gpumemleakfix | 显存泄漏修复 | 同上 |
| lanserverproperties | 局域网属性 | 同上 |

缺失的 jar 不要从本仓库找；本项目不分发任何第三方二进制。原因与替代获取方式见 [LEGAL-BOUNDARIES.md](LEGAL-BOUNDARIES.md)。

## 三、复现步骤

### 第 1 步：建立实例

1. 用启动器创建一个 **1.21.1 + NeoForge 21.1.229** 的版本隔离实例，命名为 `mc-zink`；
2. 给 Java 进程分配足够的 CPU 与内存（实测 Java 进程约 400% CPU，即 4 核）；
3. 关闭电池省电、限制帧率、垂直同步；
4. 准备一个**超平坦、无光影**的固定世界，作为唯一测试场景。

固定场景很重要：不同世界、不同视角、不同实体数量会让帧率差出 10% 以上。

### 第 2 步：构建并放置 Mesa DLL

按 [BUILD.md](BUILD.md) 第二、三章构建：

- `opengl32.dll`
- `libEGL.dll`
- `libGLESv2.dll`

把三个 DLL 放到 `<MC目录>/versions/mc-zink/` 根部，与版本 jar 同级。**不要**放到 `System32`。

### 第 3 步：构建并放置 mod

按 [BUILD.md](BUILD.md) 第三章构建 `zinkgl`、`zinkinfo`、`createopt`；`dshcore` 需自建工程。把 jar 放入实例的 `mods/`。

`createlod` 尚未实机验证，首次复现建议**不启用**；`vkglshim` 没有发布 jar，不参与运行。

### 第 4 步：应用配置

```powershell
# 以 NVIDIA 为例
copy <项目根目录>\cfgpack\config\* <MC目录>\versions\mc-zink\config\ -Recurse
```

`cfgpack/profiles/gpu-nvidia.toml` 是**配置建议清单**，不是游戏会读取的文件；按其中的键值逐项合并到对应 mod 的配置，或作为调参参考。

至少确认：

- ImmediatelyFast `fast_buffer_upload = false`（E-A）；
- AcceleratedRendering 的 `block_buffer_binding_restoring` 功能状态设为 `DISABLED` **先不要开**（E-B 未过正确性门禁）；
- Flywheel backend 为 `flywheel:instancing`；
- 超分呈现后端为 `OPENGL`；
- purescale 帧生成关闭。

不同 mod 的配置键可能随版本变化；以你实际安装版本的配置界面为准。

### 第 5 步：验证 zink 已生效

启动游戏，检查：

1. 日志中 `GALLIUM_DRIVER` 至少出现 1 次；
2. `zink` 关键字出现 ≥ 20 次；
3. `zinkinfo` 输出的渲染器字符串包含 `zink`；
4. 没有 `LIBGL_ALWAYS_SOFTWARE`；
5. 能正常进入世界、画面无异常。

如果渲染器仍是 NVIDIA 原生 GL，说明 `opengl32.dll` 没被加载。检查启动器工作目录与 DLL 位置。

### 第 6 步：测量

最少配置：

- 两套 loader：**zink**（本项目的 DLL）与**原生**（移除/改名 `opengl32.dll`，让系统库接管）；
- 一个 1Hz 帧率采样器；
- 电源、温度、频率记录工具（HWiNFO 或同类）。

流程：

1. 固定世界、固定视角、固定游戏内时间；
2. 启动客户端，等待首样本出现（约 63–75 秒），再等至少 120 个样本；
3. 记录末 30 个样本的中位数；
4. 关闭客户端，换另一套 loader，重复；
5. 按 `zink → 原生 → zink → 原生 → zink → 原生` 交错，至少 3 轮（本项目用了 9 轮，3 个变体各 3 轮）；
6. 每轮记录 loader、功率、时钟、温度、限频状态；
7. 先每轮取中位，再跨轮取中位（pooled median），最后算 zink / 原生比值。

如果采样器输出每行一个帧率，可用下面的 PowerShell 片段算末 30 帧中位数：

```powershell
$fps = Get-Content .\fps.log | Where-Object { $_ -match '^\d+(\.\d+)?$' } | ForEach-Object { [double]$_ }
$tail = @($fps | Select-Object -Last 30) | Sort-Object
if ($tail.Count -eq 0) { throw "没有有效样本" }
$mid = [int][math]::Floor($tail.Count / 2)
$median = if ($tail.Count % 2 -eq 1) { $tail[$mid] } else { ($tail[$mid - 1] + $tail[$mid]) / 2 }
"末 30 帧中位数: $median"
```

### 第 7 步：判定

| 观察 | 结论 |
|---|---|
| zink / 原生在 0.85 ~ 0.91（稳态；首块可能约 0.81） | 与项目结果同量级，复现成功 |
| 低于 0.80 | 检查电源、后台进程、DLL 是否真的生效、mod 版本 |
| 高于 1.0 | 先怀疑测量错误：窗口遮挡、原生队列被限速、场景不一致 |
| 单轮波动 ±5% ~ ±8% | 正常噪声，不要下结论 |
| 差异 > 10% 且两轮复现 | 可能是真实差异 |

## 四、验收清单

复现完成前逐项打勾：

- [ ] Minecraft 1.21.1 + NeoForge 21.1.229，版本隔离实例
- [ ] Mesa 26.2.5 + 补丁，`opengl32.dll` 379 导出、ordinal base 7、仅系统 DLL 依赖
- [ ] `opengl32.dll` 位于实例根目录，未被系统目录覆盖
- [ ] 日志确认 zink 生效（`GALLIUM_DRIVER`、`zink` 计数、渲染器字符串）
- [ ] 固定世界、固定视角、无光影
- [ ] E-A 开启；E-B 关闭；P3 补丁已打
- [ ] 原生与 zink 两套 loader 可切换
- [ ] 每轮 ≥ 120 个样本，末 30 帧取中位
- [ ] 至少 3 轮交错，每轮记录电源状态
- [ ] 有原生对照，算出同队列比值
- [ ] 结果落在 0.85 ~ 0.91，或能解释偏差

## 五、常见坑

1. **第一轮偏低**。客户端冷启动、着色器未热、JIT 未稳定；本项目第一块比值 0.808，明显低于后两块。至少跑 3 轮，或丢弃第一轮。
2. **窗口被遮挡**。Windows 会对后台/遮挡窗口限速，帧率直接失真。测量时保持窗口前台。
3. **电池态 30 帧锁**。NVIDIA Battery Boost 会把帧率锁在 30；插电并关闭省电模式。
4. **驱动更新**。596.21 之外的版本可能改变 Vulkan 提交开销；换驱动后必须重测原生对照。
5. **mod 自动更新**。Sodium / Iris / AR / IFA 的版本变化会改变 GL 调用画像；固定版本再测。
6. **光影**。开光影后 GPU 成为瓶颈，CPU 侧优化收益被压缩；不要拿光影数据与无光影数据比。
7. **`flywheel:instancing` 写法**。写成 `"INSTANCING"` 会静默回落到 indirect，Create 渲染路径完全不同。
8. **Vulkan 呈现后端**。超分后端设成 `VULKAN` 会与 zink 的 WSI 争同一窗口，表现为黑屏；改回 `OPENGL`。
9. **把 DLL 放进 System32**。会影响所有程序，且难以回滚；只放实例目录。
10. **用单轮数据下结论**。单轮 ±5% ~ ±8% 是噪声，必须交错、复现。

## 六、无法复现的部分

| 内容 | 原因 |
|---|---|
| `zoo14`–`zoo23` 候选 DLL 的逐字节行为 | 这些候选在 TACZ 场景触发 `nglGetTexImage` 卡死，已 BLOCKED，未发布 |
| PGO 构建的精确指令布局 | 依赖当时的编译器、训练集与源码状态；最终队列也未使用 PGO |
| `bench3` 采样器 | 未随公开快照；可用任意 1Hz 采样器替代 |
| 56 个 mod 的精确版本组合 | 未随快照；版本漂移会改变结果 |
| 0.858 这个精确比值 | 硬件、驱动、室温、背景进程的微小差异都会改变它 |

## 七、结果解释的边界

- 本项目的 0.88 是**无光影、1080p、超平坦、RTX 3060 Laptop** 下的结果；
- 换 CPU 可能改变瓶颈位置：zink 的翻译开销在 CPU 侧，CPU 越弱，比值可能越低；
- 换 GPU 可能改变 GPU 侧瓶颈：GPU 越弱，原生与 zink 的差距可能被 GPU 时间掩盖；
- 换驱动可能改变 fence / 提交开销，P3 的收益尤其敏感；
- 任何外推都要重新测量，不能直接引用本文数字。
