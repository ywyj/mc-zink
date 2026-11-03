# 架构与组件

本文说明 DSH-Zink 在运行时由哪些部件组成、一次绘制调用如何从 Minecraft 走到 GPU，以及仓库各目录负责什么。数字与版本以 [HANDOFF.md](../HANDOFF.md)、[STATUS.md](../STATUS.md) 和 [mesaopt/REPORT.md](../mesaopt/REPORT.md) 为准。

## 一、总体定位

Minecraft Java 版的渲染代码（Blaze3D）只认识 OpenGL。本项目不修改游戏的渲染架构，而是在 **DLL 层替换 OpenGL 实现**：

```text
┌─────────────────────────────────────────────────────────┐
│ Minecraft 1.21.1 + NeoForge 21.1.229                    │
│   Blaze3D：提交 draw call、上传缓冲区、切换 GL 状态      │
└───────────────────────┬─────────────────────────────────┘
                        │ OpenGL 3.3 core 调用（LWJGL / JNA）
┌───────────────────────▼─────────────────────────────────┐
│ opengl32.dll（Mesa 26.2.5 + 本项目补丁）                 │
│   ├─ WGL 前端：创建 GL 上下文、像素格式                  │
│   ├─ Gallium 状态机：把 GL 状态映射为管线状态            │
│   └─ zink 驱动：翻译为 Vulkan 命令、管理资源与同步       │
└───────────────────────┬─────────────────────────────────┘
                        │ Vulkan 1.3 命令（vkCmd*、vkQueueSubmit）
┌───────────────────────▼─────────────────────────────────┐
│ NVIDIA Vulkan 驱动（RTX 3060 Laptop，596.21）            │
│   ├─ 命令缓冲执行、内存管理、管线编译                    │
│   └─ kopper / WSI：交换链图像呈现到窗口                  │
└───────────────────────┬─────────────────────────────────┘
                        ▼
                       屏幕
```

**关键结论**：游戏始终以为自己在用 OpenGL。所有 Java 侧优化只能改变“游戏发出什么 GL 调用”，所有 Mesa 侧优化只能改变“这些调用如何被翻译和执行”。

## 二、启动与初始化顺序

1. 启动器选择版本 `DSH-Zink`，把工作目录设为 `<MC目录>/versions/DSH-Zink/`。
2. JVM 启动，LWJGL 通过 GLFW 创建窗口，并按 Windows 的 DLL 搜索顺序加载**实例目录下**的 `opengl32.dll`（应用目录优先于系统目录）。
3. `opengl32.dll` 内的 WGL 前端创建 OpenGL 上下文；Mesa 的 Gallium 层加载 zink 驱动（`GALLIUM_DRIVER=zink`）。
4. zink 枚举 Vulkan 物理设备、创建 `VkDevice` 与队列，kopper 为窗口创建交换链。
5. `zinkgl` 在 GL 初始化前注入环境变量（`EnvLoader`），必要时预加载依赖（`Preload`），保证 zink 拿到正确的驱动与缓存路径。
6. 进入世界后，Blaze3D 每帧发出约 2000+ 次 GL 调用，由 zink 翻译并提交。

## 三、组件详解

### 3.1 Mesa DLL（驱动本体）

| 文件 | 作用 |
|---|---|
| `opengl32.dll` | 替换系统 OpenGL 实现；**fat 单文件**构建，内含 WGL 前端、Gallium 与 zink，379 个导出，ordinal base 7 |
| `libEGL.dll` | EGL 入口；本项目主路径不使用，但随构建产物提供 |
| `libGLESv2.dll` | OpenGL ES 入口；同上 |

构建要求（详见 [BUILD.md](BUILD.md)）：

- 必须把 `wgl.c` 同时编进 `libgallium_wgl_combined` 和 `opengl32.dll`，否则 `DllMain` 不会链入，会静默产出一个“能加载但不起作用”的坏 DLL；
- 只依赖系统 DLL，不依赖 MSVC 运行库（`-Db_vscrt=mt` 静态链接）；
- shader cache 在 Windows 上默认关闭，需要打 `disk-cache-win32-*` 三个补丁、解开 meson 限制，并在 7 个编译单元里打开 `ENABLE_SHADER_CACHE`；
- 额外的 `pipeline-cache-ttl-win32.patch` 给磁盘缓存加 30 天 TTL（`MESA_SHADER_CACHE_TTL_DAYS`，0 表示关闭），避免缓存无限增长。

### 3.2 `dshcore`（核心 mod）

自研 mod，源码在 `dshcore/src/`；**构建出的 jar 不随仓库分发**（内嵌第三方代码，见 [LEGAL-BOUNDARIES.md](LEGAL-BOUNDARIES.md)）。

| 模块 | 入口 | 作用 |
|---|---|---|
| arfix | `arfix/ArFixGate.java`、`mixin/arfix/ArTextureNoReadbackMixin.java` | 绕过 AcceleratedRendering 在 zink 上的纹理回读路径，规避 `nglGetTexImage` 卡死 |
| op2r | `op2r/Opack2ReloadMod.java` | 0Pack2Reload：玩家没有改变选择时，避免一次多余的资源包重载（clean-room 实现） |
| chain | `chain/` 下 11 个类 | 连锁挖掘：按键、配置、模式、服务端校验、帧时间 HUD |
| zsync | 构建变体 `1.3.1-zsync` | 把 AR / Flywheel 的同步轮询改成 `glClientWaitSync` 非阻塞探测，减少渲染线程空等 |
| ec 兼容 | `ec/EcCompatMixinPlugin.java` | 与 EntityCulling 的 mixin 兼容插件 |

`dshcore` 的 mixin 配置分三份：`dshcore-arfix.mixins.json`、`dshcore-chain.mixins.json`、`dshcore-op2r.mixins.json`，可按需启用。

### 3.3 `zinkgl`（Java agent / mod）

源码在 `zinkgl/src/main/java/com/dsh/zinkgl/`，版本 0.4.0，使用 NeoForge ModDev 2.0.141、Java 21、NeoForge 21.1.229。

| 类 | 作用 |
|---|---|
| `EnvLoader` | 在 GL 初始化前设置 zink 相关环境变量与缓存路径 |
| `Preload` | 预加载原生库，避免运行期类加载抖动 |
| `Mixin/MainMixin` | 在启动早期插入初始化逻辑 |
| `Mixin/RenderSystemMixin` | 在渲染系统初始化处挂钩，确保环境已就绪 |
| `StreamlineProvider` | 为超分/帧生成提供 Streamline 桥接（实验） |
| `SRMatrices` | 超分所需的投影/视图矩阵处理 |
| `ZinkGL` | 模块入口与状态管理 |

`zinkgl` 的 `build.gradle.kts` 当前通过**本地绝对路径**引用 JNA 5.14.0（`<MC目录>/libraries/...`），公开构建前需改成 Maven 坐标 `net.java.dev.jna:jna:5.14.0`。

### 3.4 `zinkinfo`（诊断 mod）

版本 0.3.0。启动时打印 GL 版本、渲染器字符串、Vulkan 设备信息与 zink 状态，用于确认“到底有没有跑在 zink 上”。`KfDiag` 提供关键字过滤，避免日志被刷屏。

### 3.5 `createopt`（Create 兼容优化）

版本 1.1.0。针对 Create 模组的渲染开销做剔除：

| 类 | 作用 |
|---|---|
| `CreateOpt` | 入口与配置 |
| `Culling` | 通用剔除判定 |
| `EventCulling` | 事件驱动的可见性更新 |
| `Env` | 环境与开关 |
| `mixin/BlockEntityCullingMixin` | 方块实体剔除 |
| `mixin/EntityCullingMixin` | 实体剔除 |
| `mixin/EntityShadowMixin` | 阴影渲染剔除 |
| `mixin/ParticleCullingMixin` | 粒子剔除 |

`-Pprofile=lite` 会产出 `createopt-zink-lite`：排除 mixin 包与 `createopt.mixins.json`，只保留不含注入的轻量逻辑，便于排查“是不是 mixin 导致的问题”。

### 3.6 `createlod`（Create 方块实体 LOD）

源码在 `createlod/mod/src/main/java/com/dsh/createlod/`。核心是策略引擎：

- `policy/ModelBounds`：模型包围盒；
- `policy/Occluders`：遮挡体判定；
- `policy/PolicyEngine`：按距离、可见性、遮挡关系决定 LOD 级别；
- `scan/ScanController`：扫描与缓存；
- `mixin/BlockEntityStorageMixin`：接入方块实体存储；
- `config/LodConfig`：配置。

状态：**已构建，未实机验证**。仓库未包含 Gradle 构建脚本，需要自行搭建工程。

### 3.7 `vkglshim`（实验）

40 个 Java 源文件：`GL.java`、`GL11` 到 `GL46` 及对应的 `*C` 变体，另有 `GLCapabilities`、`GLDebugMessageCallback`、`Shim`。目标是在 Vulkan 上模拟一套 GL 接口。当前**未发布 jar**，仅作技术验证。

### 3.8 `cfgpack`（配置模板）

- `config/`：26 个纯文本配置模板，覆盖 AcceleratedRendering、ImmediatelyFast、Flywheel、Iris、Sodium、超分等；
- `profiles/`：11 个 profile，按 GPU 厂商（NVIDIA / AMD / Intel）、光影开关、超分档位、Vulkan 环境变量划分；
- `profiles/README.md`：各 profile 的适用场景与差异。

配置模板只含键与推荐值，不含个人信息。

### 3.9 `mesaopt`（补丁与构建）

| 子目录/文件 | 作用 |
|---|---|
| `patches/disk-cache-win32-*.patch` | Windows 磁盘 shader cache 的三个补丁 |
| `patches/meson-allow-shader-cache-windows.patch` | 解开 meson 对 Windows shader cache 的限制 |
| `patches/meson-fat-*.patch` | fat 单文件 `opengl32.dll` 的两个 meson 改动 |
| `patches/pipeline-cache-ttl-win32.patch` | 管线缓存 30 天 TTL |
| `patches/zoo2/0001-p3-remove-frontbuffer-flush-wait.patch` | P3：删除每帧 frontbuffer fence 等待（默认开启） |
| `patches/zoo2/1ca365b2.patch` 等 3 个 | Mesa 上游 tc 相关提交（保留原始 From/Subject/hash） |
| `patches/zoo1/` | 实验层：infra + P1 kopper 同步 present + P2 batch 窗口 + P3 no-fb-wait，带环境变量开关，仅历史参考 |
| `patches/layers/layer_ctl.py` | 补丁层管理：`set <layer>`、sha256 校验、可回滚 |
| `patches/verify_patch_hunks.py` | 检查补丁 hunk 是否能干净应用 |
| `REPORT.md` / `WORKSPACE.md` | 构建记录、工具链版本、门禁 marker |

## 四、目录职责

| 目录 | 职责 | 是否含可执行产物 |
|---|---|---|
| `mesaopt/` | Mesa 补丁、构建脚本、层管理与构建记录 | 否（只有补丁与文本） |
| `zinkgl/` | 启动期环境注入与 GL/Streamline 桥接 | 否（源码 + Gradle 脚本） |
| `zinkinfo/` | 诊断信息输出 | 否 |
| `dshcore/` | arfix、op2r、chain、zsync | 否（源码） |
| `createopt/` | Create 渲染剔除 | 否（源码 + Gradle 脚本） |
| `createlod/` | Create 方块实体 LOD | 否（源码） |
| `vkglshim/` | Vulkan 上的 GL 兼容层（实验） | 否（源码） |
| `cfgpack/` | 配置模板与 profile | 否（纯文本） |
| `docs/` | 本项目文档 | 否 |

顶层还有 `HANDOFF.md`、`STATUS.md`、`ZINK_OPTIMIZATION_RESULTS.md` 等原始记录，保留当时的数据与结论，便于追溯。

## 五、三项优化的作用点

### E-A：改变上传路径（Java / GL 侧）

```text
开启 fast_buffer_upload：
  游戏 glBufferSubData
    → Mesa u_upload 暂存缓冲
    → vkCmdCopyBuffer（额外一次拷贝 + 同步）
    → 目标缓冲区

关闭后：
  游戏 glBufferData(DISCARD_WHOLE_RESOURCE)
    → 直接整块重分配 / orphan 快路径（src/mesa/main/bufferobj.c:292-318）
    → 目标缓冲区
```

每帧 76–81 次上传，单次省 9.5–10 微秒，合计约 0.73–0.77 毫秒/帧。

### E-B：减少状态查询（Java / GL 侧）

AcceleratedRendering 的 `block_buffer_binding_restoring` 每帧调用约 89 次 `glGetIntegeri_v` 恢复绑定。关闭后这两条统计站点归零，总 GL 调用从 2168 降到 2055 次/帧。代价是渲染正确性依赖“裸状态”，因此需要开光影的 6 场景截图门禁；该门禁未通过，**不进入默认配置**。

### P3：删除呈现前的 fence 等待（Mesa / zink 侧）

原始 `zink_flush_frontbuffer()` 在提交当前帧后，会等待该 batch 的 `flush_completed` fence（`zink_screen.c:1937-1940`）。P3 删除这次等待；补丁与 Mesa 上游提交 33f0fecc 等价，没有引入新的同步原语。收益 +2% ~ +4%，queue_final 相邻配对中位 +6.6%。

## 六、设计取舍与边界

1. **不改游戏渲染架构**。不重写 Blaze3D，不引入第二套渲染器；所有改动都在 DLL、mod、配置层。这让方案可逆，也限制了上限。
2. **Java 侧只做“少发/改发 GL 调用”**。不伪造 GL 实现，不拦截绘制结果；E-A / E-B 都是配置级改动，可单独回退。
3. **Mesa 侧只做小补丁**。P3 是单 hunk，语义与上游提交一致，便于合并回上游；zoo1 那类多开关实验层只保留历史记录，不进入默认。
4. **不打包第三方二进制**。仓库是“可读、可构建、可审计”的源码与文档集合，不是开箱即用的整合包。
5. **测量优先于感觉**。任何优化必须有同队列对照；没有对照的“变快了”不写进结论。

## 七、已知不一致

- 5 个 mod 的 `META-INF/neoforge.mods.toml` 仍写着 `license = "MIT"`（`zinkgl`、`zinkinfo`、`createopt`、`createlod`、`vkglshim`），与仓库根目录的 PolyForm Noncommercial 1.0.0 不一致。这是历史遗留，以根目录 [LICENSE](../LICENSE) 为准；后续发布前会统一同步。
- `zinkgl` / `zinkinfo` 的 Gradle 脚本引用本机 `<MC目录>` 下的 JNA jar，公开构建前需改为 Maven 坐标。
- `meson-fat-*.patch` 的 a 侧文件名是 `meson.build.orig`，在干净源码树上需要先准备 `.orig` 文件，或把 a 侧改成 `meson.build`。
