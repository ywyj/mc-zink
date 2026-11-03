[English](README.en.md) | 中文

# mc-zink：在 Minecraft 上把 OpenGL 转译成 Vulkan

> **一句话结论**：Mesa zink 能在 Minecraft 1.21.1（NeoForge）上跑起来，稳态帧率约为同一台机器上原生 NVIDIA OpenGL 的 **0.88**（pooled median 182 对 212）。**没有超过原生**，差约 12%。这个数字、以及差距具体来自哪些调用，就是本项目想讲清楚的事。

- 代码许可：**PolyForm Noncommercial 1.0.0**（允许修改，禁止任何商业用途）。
- 文档许可：**CC BY-NC-SA 4.0**。
- **本仓库不包含任何第三方二进制**：不带 mod jar、光影包、NVIDIA DLL、游戏本体或反编译源码。缺什么、为什么、怎么自行获取，见[第八章](#八不可分发组件清单)。
- 与 Mojang、Microsoft、NVIDIA、Mesa、Create 均无隶属或赞助关系。

---

## 一、项目是什么

先解释三个名词：

- **OpenGL**：一套跨平台的图形编程接口（API）。Minecraft Java 版的渲染代码（Blaze3D 引擎）通过它画方块、实体和界面。
- **Vulkan**：新一代底层图形接口，显式管理命令缓冲、内存和同步，驱动开销更低，但调用更啰嗦。
- **zink**：Mesa 开源图形栈里的一个 Gallium 驱动，作用是把 OpenGL 调用**翻译**成 Vulkan 命令。它不是 Vulkan 渲染器，而是一层转译层。

本项目在 Windows 上做了三件事：

1. **把 zink 编成可直接替换的 `opengl32.dll`**。Minecraft 启动时从实例目录加载这个 DLL，游戏仍然走 OpenGL 路径，但每次 GL 调用实际进入 Mesa 的 zink 驱动，再由它下发 Vulkan 命令给 NVIDIA 驱动。
2. **写配套 mod 与工具**，处理 zink 路径上会卡死或回读的兼容问题，并把可调项固化成配置模板。
3. **用可复现的测量协议量化每一项优化**，包括大量被判负、被否决的方案——否决记录和收益记录同样重要。

**它不是**：不是把 Minecraft 改写成原生 Vulkan 渲染器；不是官方 Vulkan 后端；不是 OptiFine 的替代品；不修改游戏的渲染架构。转译层的开销是结构性的，本项目的目标是**尽量缩小**它，而不是假装它不存在。

---

## 二、为什么做

### 起点：两台低配笔记本，和一台机械动力服务器

这个项目不是先有选题、再找场景。它来自一个很具体的问题：我平时和朋友一起玩 Create（机械动力）整合包，其中两位用的机器偏弱——一台轻薄本，一台骁龙笔记本。整合包里的齿轮、传动杆、流水线和光影，让这两台机器都跑得很吃力。

我想试试能不能绕开 Minecraft 默认的 OpenGL 路径，把帧率抬起来：

- 用 Mesa 的 zink 把 OpenGL 调用转译成 Vulkan；
- 在渲染链路上做**超分辨率**（用较低的内部渲染分辨率渲染，再放大回目标分辨率）；
- 用**插帧**（frame generation）在真实帧之间生成中间帧；
- 再配合 Sodium（钠）等优化 mod，压低 CPU 侧的渲染开销。

结果可以分成两半。

**成功的部分**：超分辨率（FSR1，1.5×）和插帧都成功运行过；Sodium（钠）与 Iris 光影也能正常启动，Vulkan 呈现路径在实验配置下可用，没有黑屏或死锁。

**没达到预期的部分**：zink 转译层本身比同一台机器上的原生 NVIDIA OpenGL 慢约 12%（0.88×）。超分和插帧省下来的时间，被这层结构性开销吃掉了一部分。我原本还想进一步利用 Vulkan 的显式特性——例如多线程命令录制、更细粒度的同步控制——把 CPU 开销压下去、把帧率提上来，但这部分最终没有做出来，项目也没有达到预期。

所以这个项目的真实状态是：**功能验证成功，性能没有达到预期**。我把它公开出来，是希望有能力维护的人接手——代码、测量数据和失败记录都在仓库里，路已经铺到这里，剩下的可以接着走。

### 技术动机

动机有三个：

1. **可行性**：Minecraft 的渲染负载以大量小 draw call、频繁状态切换、每帧缓冲区上传为主，这正是转译层最容易吃亏的场景。它到底能不能跑、能跑到什么程度？
2. **量化差距**：不要“感觉挺流畅”，要一个可复现的数字，并且把差距拆到具体调用上（每帧多少次 `glBufferSubData`、多少次 `glGetIntegeri_v`、fence 等了多久）。
3. **沉淀**：把有效的优化做成配置或 Mesa 补丁，把无效的方案记录在案，避免后来者重复踩坑。

### 核心结果

测量协议：9 轮交错队列，1Hz 采样，取稳态末窗口的 pooled median。详见[测量方法](docs/MEASUREMENT.md)。

| 队列 | pooled median（帧/秒） | 相对原生 OpenGL |
|---|---:|---:|
| zink + 缓存 + E-A | **182** | **0.858** |
| zink + zoo1 + P3 + E-A | **188** | **0.887** |
| 原生 NVIDIA OpenGL + E-A | **212** | 1.000 |

按轮次分块计算的比值为 0.808 / 0.910 / 0.854；第一块受冷启动慢热影响明显偏低。综合后对外统一口径：

> **zink ≈ 0.88 ± 0.03 原生 OpenGL，差距约 12%。**

**作废的旧数字**：早期记录里的 0.63–0.71，以及更早的 0.555，都是在不同测量协议（窗口遮挡、后台限速、电池降频）下得到的，**不可与上表比较**。本项目只承认同一协议、同一电源状态、带原生对照的队列数据。

### 差距在哪

稳态每帧约 2168 次 GL 调用，其中纹理类 673、uniform 类 663、绑定类 481、绘制 91。差距不平均分布：绘制调用单价约 1.75 微秒，绑定约 289 纳秒，uniform 约 192 纳秒——但乘以每帧次数后，各自都是上百微秒。另一处明显开销是每帧一次的 `glClientWaitSync` 无限等待 fence 完成，折算约 355 微秒/帧（约 7.7% 墙钟）；不过加深管线、加长队列都判负，说明这是 CPU 吞吐受限的表现，不是可以简单回收的死时间。

---

## 三、架构与组件

### 数据流

```text
Minecraft 1.21.1（Blaze3D 渲染代码）
        │  发起 OpenGL 调用（经 LWJGL / JNA）
        ▼
opengl32.dll（Mesa 26.2.5 + 本项目补丁，单文件 fat 构建，379 个导出）
        │  Gallium zink 驱动：把 GL 状态、绘制、上传翻译成 Vulkan 命令
        ▼
NVIDIA Vulkan 驱动（RTX 3060 Laptop，596.21）
        │  kopper / WSI（窗口系统集成）把交换链图像呈现到窗口
        ▼
屏幕
```

关键点：**游戏完全不知道 Vulkan 的存在**。它看到的仍是 OpenGL 上下文；zink 在 DLL 内部完成翻译。因此所有 Java 侧优化（E-A、E-B）都作用在“减少或改造 GL 调用”上，而 Mesa 侧优化（P3）作用在“减少翻译后的等待”上。

### 组件表

| 组件 | 形态 | 职责 | 状态 |
|---|---|---|---|
| Mesa DLL（`opengl32.dll`、`libEGL.dll`、`libGLESv2.dll`） | 二进制，需自行构建 | zink 驱动本体；`opengl32.dll` 为 fat 单文件 | 基于 Mesa 26.2.5 |
| `dshcore` | mod 源码（jar 不随仓库） | AR 纹理回读修复（arfix）、0Pack2Reload（op2r）、连锁挖掘（chain）、zsync、帧时间 HUD | 1.3.1-zsync |
| `zinkgl` | mod 源码 | 启动期注入 zink 环境变量；GL / Streamline 桥接 | 0.4.0 |
| `zinkinfo` | mod 源码 | 打印 GL / Vulkan 诊断信息 | 0.3.0 |
| `createopt` | mod 源码 | Create 方块实体、实体、粒子的剔除 | 1.1.0（含 lite 变体） |
| `createlod` | mod 源码 | Create 方块实体 LOD | 已构建，未实机验证 |
| `cfgpack` | 配置模板 | 26 个配置文件 + 11 个 profile（GPU 厂商、光影开关、超分档、Vulkan 环境） | 纯文本 |
| `mesaopt` | 补丁、脚本、构建记录 | Mesa 补丁、补丁层管理、构建门禁 | 26.2.5 |
| `vkglshim` | 源码（实验） | 在 Vulkan 上模拟 GL 的兼容层 | 未发布 jar |

`zsync` 是 `dshcore` 里的同步改造：把 AcceleratedRendering / Flywheel 的同步轮询改成 `glClientWaitSync` 非阻塞探测，避免渲染线程空等。它随 v3.5.1 发布，但**运行期是否真正生效仍待实机确认**。

---

## 四、关键优化与数据

三条主线，收益都是在同一队列、带原生对照的测量里得到的；口径与置信度见[测量方法](docs/MEASUREMENT.md)。

### E-A：ImmediatelyFast 关闭 `fast_buffer_upload`

- **做什么**：把 ImmediatelyFast 的 `fast_buffer_upload` 设为 `false`（v3.2 起进入默认配置）。
- **为什么有效**：开启时，每帧 76–81 次 `glBufferSubData` 走 Mesa 的 `u_upload` 暂存缓冲，再额外发一次 `vkCmdCopyBuffer`；关闭后改走 `glBufferData` 的 `DISCARD_WHOLE_RESOURCE` 整块重分配快路径（`src/mesa/main/bufferobj.c:292-318`），省掉一次拷贝和一次同步。
- **数据**：P1b 实测 +4.0%（原始值 +7.6%），P1 漂移校正后 +21.5%（该值被污染，明显夸大）；取保守区间 **+4% ~ +8%**，反推约 0.73–0.77 毫秒/帧（76–81 次 × 约 9.5 微秒）。
- **旁证**：E-A 生效后，GL 调用统计里 `glBufferSubData` 站点全部归零，`GlStateManager._glBufferData` 升到 68.24 次/帧。

### E-B：AcceleratedRendering 关闭缓冲区绑定恢复

- **做什么**：把 `[core_settings.block_buffer_binding_restoring].feature_status` 设为 `"DISABLED"`。
- **为什么有效**：该功能为了“保险”在每帧恢复缓冲区绑定，代价是约 89 次 `glGetIntegeri_v` 查询；关掉后这两条统计站点（1275 / 1276）从每帧 89 次降到 0，总调用从 2168 降到 2055。
- **数据**：P1 +12.8%、P1b +4.5%，取保守 **+4% ~ +5%**，约 0.40 毫秒/帧。
- **为什么没进默认**：跳过状态恢复后，渲染正确性依赖“裸状态”恰好正确。开光影的 6 场景截图门禁（`shaders/eopt/SMOKE_SHADERON_CHECKLIST.md`，本机工作区文件，未随公开快照）**没有通过**，所以只作为可选项记录，不进入默认配置。风险等级：中。

### P3：删除每帧的 frontbuffer flush fence 等待

- **做什么**：删掉 `zink_flush_frontbuffer()` 中每帧一次的 `util_queue_fence_wait(&bs->flush_completed)`（原始代码在 `zink_screen.c:1937-1940`）。语义上等价于 Mesa 上游提交 `33f0fecc`。
- **为什么有效**：该补丁与 Mesa 上游提交 `33f0fecc` 等价；去掉这次等待后实测收益为正，说明它对当前帧提交没有带来可观测收益。
- **数据**：mix1 场景 +2.0% ~ +3.6%；queue_final 相邻配对 +7.7% / −2.6% / +6.6%，中位 +6.6%；对外取保守 **+2% ~ +4%**。
- **形态**：测量时用的是 zoo1 的 `MESA_ZINK_NO_FB_WAIT` 开关（B 队列）；量产形态是 `mesaopt/patches/zoo2/` 里的单 hunk 补丁，**无环境变量开关，默认开启**；产物 `opengl32.zoo2.dll`，17,421,824 字节，379 个导出。

### 被否决的方案

否决记录不是失败清单，而是“此路已走过”的证据。以下均为同队列或同场景 A/B 结果：

| 方案 | 结果 |
|---|---|
| `ZINK_DEBUG=noopt` | −18% ~ −27% |
| `GALLIUM_THREAD=0` | −13% ~ −18% |
| `flushsync` | −6% ~ −14% |
| `ZINK_DESCRIPTORS=lazy` | 无收益 |
| `ZINK_DEBUG=rp` | 无收益 |
| kopper 同步 present（P1 / P4） | −1.5% ~ −4% |
| P2 batch 窗口 5 → 12 | 无收益 |
| Mesa 26.3.0-rc1 整树 | +0% ~ +2.5%，在噪声内，不采纳 |
| H2b 加深管线 | −2.8% |
| Sodium render-ahead 4 → 32 | −5% ~ −6% |
| glthread | −2.8% ~ −5.2% |
| LTO（链接时优化） | +1.1% ± 1.2%，噪声内 |
| PGO（按配置引导优化） | 历史 +8.2%，后续基线已含，最终队列未再使用 |

---

## 五、快速开始与构建

本项目**不提供现成二进制**，需要自己构建 Mesa 和 mod。完整步骤见 [docs/BUILD.md](docs/BUILD.md)，材料清单见 [docs/REPRODUCE.md](docs/REPRODUCE.md)。

最短路径：

1. 准备 Windows 10/11 x64、Visual Studio Build Tools（MSVC + Windows SDK）、Meson、Ninja、Python 3.14、JDK 21。
2. 下载 Mesa 26.2.5 源码，按顺序应用 `mesaopt/patches/` 下的补丁（shader cache、fat 单文件、zoo2 P3）。
3. 用 meson 配置并构建，得到 `opengl32.dll`、`libEGL.dll`、`libGLESv2.dll`；用导出表门禁检查（379 个导出、ordinal base 7、仅依赖系统 DLL）。
4. 构建 `zinkgl`、`zinkinfo`、`createopt`（`dshcore` 需自建工程），连同配置模板放入 `<MC目录>/versions/mc-zink/`。
5. 启动游戏，确认 `GL_RENDERER` 含 zink 字样，再按 [docs/MEASUREMENT.md](docs/MEASUREMENT.md) 的方法测量。

---

## 六、测量方法

一句话版本：**9 轮交错、1Hz 采样、每轮取稳态末 30 帧中位数、跨轮取 pooled median**；必须有原生对照，必须记录电源状态，单轮 ±5% ~ ±8% 视为噪声。

完整协议、每轮原始值、E-A / E-B / P3 的测量设计与置信度，见 [docs/MEASUREMENT.md](docs/MEASUREMENT.md)。这里只强调三条铁律：

1. **不同队列的绝对值不可比**。只比同一轮交错队列内的比值。
2. **没有原生对照的加速比不算数**。同一场景、同一时段、同一次启动器会话。
3. **电源状态必须记录**。电池态会触发 30 帧硬锁，温度/频率波动能让同配置两次测量差 7% ~ 12%。

---

## 七、限制与风险

1. **转译开销是结构性的**。每个 GL 调用都要翻译、批处理、提交；差距集中在少数重型调用和内部等待上，不可能靠调参抹平。本项目的诚实结果是 0.88，不是 1.0。
2. **官方 Vulkan 后端仍是实验性**。Minecraft 新版线（26.2 及以后）自带的官方 Vulkan 渲染后端仍标记为 Experimental；本项目没有在其上验证，也不依赖它。此为上流状态，置信度中，未在仓库内复核。
3. **光影口径不同**。开启光影后 GPU 成为瓶颈，CPU 侧优化（E-A / E-B / P3）的收益会被压缩；本项目的全部数字均为**无光影**。
4. **候选 DLL 在 TACZ 场景卡死**。zoo14–23 候选在 TACZ（枪械 mod）的 depot 场景、AcceleratedRendering 纹理回读时，渲染线程永久阻塞在 `nglGetTexImage`，未解决；v3.5.1 改用 pgo2 基线规避此问题，同世界正常。
5. **优化不可移植到原生 Vulkan**。E-A 改的是 GL 上传路径，E-B 改的是 GL 状态查询，P3 改的是 zink 的 GL 前端 fence；一旦换成原生 Vulkan 渲染后端，这些优化**全部失效**。
6. **单机单卡结论**。测试平台为 Ryzen 7 5800H + RTX 3060 Laptop（596.21 驱动）、1080p、超平坦世界、56 个 mod；不外推到其他 CPU/GPU/驱动组合。
7. **未闭环项**：`createlod` 已构建但未实机；`zsync` 运行期生效待确认；Vulkan 呈现后端仍属实验性（会与 zink 的 WSI 争同一窗口导致黑屏，默认使用 OpenGL 呈现）；鼠标转动时的轻微卡顿未修。
8. **无担保**。按“现状”提供，不承诺适用于任何特定目的；自行承担修改驱动、替换系统 DLL 的风险。

---

## 八、不可分发组件清单

本仓库只放**文档、自研源码、Mesa 补丁、配置模板**。以下内容一律不随仓库分发，也不放在 Release 附件里。完整原因、许可矩阵与替代获取方式见 [docs/LEGAL-BOUNDARIES.md](docs/LEGAL-BOUNDARIES.md)。

### 8.1 第三方 mod jar（含本项目 dshcore 的构建产物）

| 文件 | 是什么 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| `alltheleaks-*.jar` | 内存泄漏修复 mod | MIT，许可允许再分发；本项目统一不打包第三方二进制，避免版本漂移与担保责任 | 内存泄漏修复缺失，长时间游玩内存上涨 | Modrinth / CurseForge 作者页 |
| `colorwheel-*.jar` | Flywheel 的 instancing 渲染后端 | LGPLv3，jar 内未附许可全文；再分发需履行 LGPL 义务（源码/替换权） | Flywheel 的 instancing 后端缺失，Create 渲染回退 | 作者发布页 |
| `cupboard-*.jar` | 依赖库 mod | 作者保留所有权利（ARR），禁止再分发 | 依赖它的整合包启动报错 | 作者发布页 |
| `ocuwheel-*.jar` | OC 与 Flywheel 的兼容 mod | BRSSLA V2.0，仓库内无许可全文，权利不明 | OC 与 Flywheel 的兼容层缺失 | 作者发布页 |
| `Opticores-*.jar` | 视觉/光影兼容 mod（修改版） | MIT 修改版（dshfix），修改版再分发需保留声明 | 视觉/光影兼容项缺失 | 作者页；或按 MIT 自行修改 |
| `sentrymechanicalarm-*.jar` | 机械臂哨兵 mod | MIT | 机械臂哨兵功能缺失 | 作者发布页 |
| `gpumemleakfix-*.jar` | 显存泄漏修复 mod（修改版） | ARR，且文件名表明是修改版 | 显存泄漏修复缺失 | 作者页获取原版 |
| `lanserverproperties-*.jar` | 局域网属性配置 mod | MIT | 局域网属性配置缺失 | 作者发布页 |
| `dshcore-1.3.1-zsync.jar` | 本项目核心 mod（arfix、op2r、chain、zsync、HUD） | 自研代码 + 内嵌第三方代码（EntityCulling，Protective License 禁商用）形成混合许可，不能整体按 PolyForm 分发 | AR 回读修复、连锁、op2r、HUD 全部缺失 | 源码可读；自行搭建工程构建（仓库未附 dshcore 的 Gradle 脚本），仅限非商业自用 |

### 8.2 光影包

| 文件 | 是什么 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| `ComplementaryReimagined_r5.9.3.zip` | 光影包 | Complementary License Agreement 1.7 明确禁止再分发 | 光影不可用 | 作者官网 / Patreon 发布页 |
| `photon-v1.3b.zip` | 光影包 | 自定义许可允许再分发，但禁止在可获利平台分发或收费；平台定性有风险 | 光影不可用 | 作者 GitHub Releases |

### 8.3 NVIDIA 专有 DLL

| 文件 | 是什么 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| `nvngx_dlssg.dll` | DLSS 帧生成运行库 | NVIDIA 专有组件，EULA 禁止裸再分发 | DLSS-G 帧生成不可用 | 官方驱动 / 支持的游戏自带 |
| `sm86_backend.dll` | 特定超分后端运行库 | 同上，且来源不明 | 特定超分后端不可用 | 官方驱动 |
| `version.dll` | 版本代理/劫持型 DLL | 来源与授权均不明，不应传播 | 版本伪装失效 | 不建议获取 |
| `libSuperResolution+win64+release.dll` 等 `libSuperResolution*.dll` | NVIDIA Streamline / NGX 超分运行时 | 专有组件，不能裸再分发 | 超分运行时缺失 | NVIDIA DLSS SDK / 游戏自带 |

### 8.4 反编译源码

| 文件 | 是什么 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| Minecraft 反编译源码（`decomp/`、`.ref/`） | 反编译得到的 Minecraft 源码 | Mojang EULA 禁止分发游戏代码 | 无法直接阅读 MC 实现 | 用合法拥有的游戏 jar 自行反编译，仅供个人学习 |
| Create 反编译源码 | 反编译得到的 Create 源码 | Create 自身许可 + Mojang 映射限制 | 无法直接阅读 Create 实现 | 自行反编译，仅供个人学习 |

### 8.5 启动器

| 文件 | 是什么 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| PCL2 启动器 | Minecraft 启动器，目录含用户配置 | 第三方软件，未核实再分发许可，且包含用户配置 | 启动器功能缺失 | 官方发布页 |

### 8.6 SDK

| 文件 | 是什么 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| Streamline SDK | NVIDIA 超分/帧生成 SDK | NVIDIA SDK 许可，禁止再分发 | 无法编译超分/帧生成相关组件 | NVIDIA 开发者站点 |

---

## 九、免责声明

1. **非官方**：本项目与 Mojang Studios、Microsoft、NVIDIA、Mesa 项目、Create 及其作者均无隶属、赞助或背书关系。
2. **非盈利**：本项目不销售、不收费、不接广告、不做付费下载；仅供学习、研究与个人非商业使用。非盈利不豁免版权——不因此获得分发他人作品的权利。
3. **不含游戏本体**：不提供 Minecraft、NeoForge、Create 或其任何商业素材；使用需自行购买并遵守各自的最终用户许可协议。
4. **不含第三方二进制**：清单见第八章；仓库与 Release 均不放第三方 jar、光影、DLL 或反编译源码。
5. **商标归属**：Minecraft © Mojang Studios；NVIDIA、DLSS、Streamline 是 NVIDIA 的商标；Create、Flywheel 归其作者所有。文中出现仅作描述性指代。
6. **许可范围**：本仓库代码按 PolyForm Noncommercial 1.0.0 授权，文档按 CC BY-NC-SA 4.0 授权，Mesa 补丁保留 MIT；第三方组件仍按其自身许可。
7. **无担保**：软件按“现状”提供，不附带任何明示或默示担保；替换图形驱动可能导致崩溃、存档损坏或数据丢失，风险自负。
8. **下架机制**：若权利人认为本仓库内容侵权，请通过仓库 Issue 联系；确认后会在合理时间内删除相关内容。

---

## 十、许可证

| 范围 | 许可证 | 你能做什么 | 你不能做什么 |
|---|---|---|---|
| 本项目代码与配置模板（`zinkgl/`、`zinkinfo/`、`dshcore/`、`createopt/`、`createlod/`、`vkglshim/`、`cfgpack/`、脚本） | **PolyForm Noncommercial 1.0.0**（全文见 [LICENSE](LICENSE)） | 使用、复制、修改、创作衍生作品、非商业分发 | 任何商业用途：付费整合包、付费下载、广告变现、商业产品内置、以盈利为目的的托管服务 |
| 文档（`README.md`、`docs/`、各子目录说明） | **CC BY-NC-SA 4.0**（说明见 [LICENSE-docs](LICENSE-docs)） | 复制、分发、修改、改编，需署名、非商业、相同方式共享 | 商业使用；删除署名；用技术措施限制他人合法使用 |
| `mesaopt/patches/` | **MIT**（Mesa 上游许可） | 使用、修改、分发、再许可，需保留版权与许可声明 | 去掉版权声明；作者不为使用后果负责 |

**为什么 `mesaopt/patches/` 是例外**：这些补丁是对 Mesa（MIT 许可）的修改，属于 MIT 代码的衍生作品。保留 MIT 既符合上游许可，也方便把有价值的补丁合并回 Mesa 上游。仓库根目录的 PolyForm 许可**不覆盖**这部分。

**注意**：

- PolyForm Noncommercial 1.0.0 **不是 OSI 定义的开源许可**，GitHub 会把它识别为 “Other”。它允许修改与再分发，但禁止商业用途，这是有意的选择。
- `dshcore` 的构建产物内嵌第三方代码，**不能**整体按 PolyForm 分发；仓库只公开自研源码。
- 各 mod 的 `META-INF/neoforge.mods.toml` 中仍有历史遗留的 `license = "MIT"` 字段，与根目录许可不一致；以根目录 [LICENSE](LICENSE) 为准，后续发布前会统一同步。

---

## 项目状态与维护

当前状态：**个人业余项目，功能验证完成，性能未达预期，已暂停推进**。作者没有足够时间继续做下去，仓库里的代码、数据、测量协议和失败记录都完整保留。

如果你想接手或参与：

- 先从 [STATUS.md](STATUS.md) 和 [HANDOFF.md](HANDOFF.md) 看起，那里有当前进度、已知问题和待办；
- 最值得继续的方向是把 CPU 侧开销压下去（见第二章「差距在哪」以及 Vulkan 多线程、同步相关的未完成设想）；
- 通过仓库 Issue 说明你想做什么，避免多人重复劳动；
- 代码许可为 PolyForm Noncommercial 1.0.0，非商业使用与修改都允许。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构、组件、GL → zink → Vulkan 数据流、目录职责 |
| [docs/MEASUREMENT.md](docs/MEASUREMENT.md) | 测量协议、每轮数据、三项优化的测量设计与置信度 |
| [docs/LEGAL-BOUNDARIES.md](docs/LEGAL-BOUNDARIES.md) | 不可分发清单、第三方许可矩阵、仓库卫生规则 |
| [docs/BUILD.md](docs/BUILD.md) | Mesa 补丁与 meson 构建、Gradle 构建、DLL 放置 |
| [docs/REPRODUCE.md](docs/REPRODUCE.md) | 复现步骤、需自备的材料、验收清单 |
| [SOURCES.md](SOURCES.md) | 公开快照的文件来源与清洗说明 |
| [HANDOFF.md](HANDOFF.md) | 项目总交接记录 |
| [ZINK_OPTIMIZATION_RESULTS.md](ZINK_OPTIMIZATION_RESULTS.md) | 优化测量原始记录 |
| [STATUS.md](STATUS.md) | 状态、性能剖析与已知问题 |
