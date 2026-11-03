# 构建指南

本文说明如何从源码构建 DSH-Zink 的三部分：Mesa（`opengl32.dll` 等）、Java mod、以及配置与部署。所有命令在 Windows PowerShell 下执行。

> 本仓库**不提供二进制**。构建需要自行准备工具链与 Mesa 源码，详见 [REPRODUCE.md](REPRODUCE.md)。

## 一、工具链

| 工具 | 版本（本项目实测） | 用途 |
|---|---|---|
| Visual Studio Build Tools | VS 2026 BuildTools 18.10 | MSVC 编译器 |
| MSVC | 14.51.36231 | 编译 Mesa |
| Windows SDK | 10.0.26100.0 | Windows 头文件与库 |
| Meson | 1.12.1 | 构建系统 |
| Ninja | 1.13.2 | 后端 |
| win_bison | 3.7.4 | Mesa 语法分析 |
| win_flex | 2.6.4 | Mesa 词法分析 |
| Python | 3.14 | Meson 依赖 |
| JDK | 21 | 编译 mod |
| Gradle | 8.x（仓库未附 wrapper） | 编译 mod |

安装完成后确认：

```powershell
cl
meson --version
ninja --version
python --version
java -version
gradle --version
```

`cl` 需要在 “x64 Native Tools Command Prompt for VS” 里运行，或先执行 `vcvars64.bat`。

## 二、构建 Mesa

### 2.1 准备源码

下载 Mesa **26.2.5** 官方源码包（约 68,652,756 字节），解压到 `mesaopt/src/`：

```powershell
cd <项目根目录>\mesaopt
tar -xf mesa-26.2.5.tar.xz -C src
```

得到 `<项目根目录>\mesaopt\src\mesa-26.2.5\`。

### 2.2 应用补丁

补丁位于 `mesaopt/patches/`。**按下列顺序**应用；每一步都在 Mesa 源码根目录执行 `patch -p1`。

#### 第一组：Windows 磁盘 shader cache

```powershell
cd <项目根目录>\mesaopt\src\mesa-26.2.5
patch -p1 < ..\..\patches\disk-cache-win32-generic.patch
patch -p1 < ..\..\patches\disk-cache-win32-os-header.patch
patch -p1 < ..\..\patches\disk-cache-win32-os-impl.patch
patch -p1 < ..\..\patches\meson-allow-shader-cache-windows.patch
```

可选：给磁盘缓存加 30 天 TTL（`MESA_SHADER_CACHE_TTL_DAYS`，0 表示关闭）：

```powershell
patch -p1 < ..\..\patches\pipeline-cache-ttl-win32.patch
```

打完这组补丁后，还需要在 7 个编译单元里打开 `ENABLE_SHADER_CACHE`（具体文件清单见 `mesaopt/REPORT.md` 的构建记录）。漏掉这一步，缓存不会生效，表现为每次启动都重新编译着色器。

#### 第二组：fat 单文件 `opengl32.dll`

```powershell
copy src\gallium\targets\wgl\meson.build src\gallium\targets\wgl\meson.build.orig
copy src\gallium\targets\libgl-gdi\meson.build src\gallium\targets\libgl-gdi\meson.build.orig

patch -p1 < ..\..\patches\meson-fat-wgl.patch
patch -p1 < ..\..\patches\meson-fat-opengl32.patch
```

**注意**：这两个补丁的 a 侧文件名是 `meson.build.orig`，所以必须先复制出 `.orig` 文件；否则补丁无法应用。也可以把补丁里的 a 侧路径改成 `meson.build` 后再打。

fat 构建的关键不是“把文件拼在一起”，而是让 `wgl.c` 同时编进 `libgallium_wgl_combined` 和 `opengl32.dll`。否则 `DllMain` 不会被链入，最终得到一个**能加载但完全不工作**的 DLL——这是本项目踩过的坑，必须用 2.4 的门禁检查。

#### 第三组：P3（默认优化）

```powershell
patch -p1 < ..\..\patches\zoo2\0001-p3-remove-frontbuffer-flush-wait.patch
```

这一条删除 `zink_flush_frontbuffer()` 中每帧一次的 `flush_completed` fence 等待，语义等价于 Mesa 上游提交 `33f0fecc`。

`zoo2/` 下的另外三个补丁（`1ca365b2.patch`、`32658eec.patch`、`5710cae1.patch`）是上游 tc 提交，用于 zoo3 的归因 A/B，**不是默认构建的必需项**；不要误把它们当作 P3 的一部分。

#### 可选：zoo1 实验层

`zoo1/` 是带环境变量开关的实验层（infra、P1 kopper 同步 present、P2 batch 窗口、P3 no-fb-wait），仅用于历史复现，**不进入默认构建**。要复现实验：

```powershell
patch -p1 < ..\..\patches\zoo1\0001-zoo1-infra.patch
patch -p1 < ..\..\patches\zoo1\0002-p1-kopper-sync-present.patch
patch -p1 < ..\..\patches\zoo1\0003-p2-batch-window.patch
patch -p1 < ..\..\patches\zoo1\0004-p3-no-fb-wait.patch
```

它依赖 `zink_zoo.h` 与 `zink_zoo_init()`，默认全部关闭，行为应等于未打补丁版本。

### 2.3 配置与编译

```powershell
cd <项目根目录>\mesaopt\src\mesa-26.2.5
meson setup build --buildtype=release -Db_ndebug=true -Db_vscrt=mt `
  -Dplatforms=windows -Dgallium-drivers=zink -Dvulkan-drivers= `
  -Dopengl=true -Degl=disabled -Dgles1=disabled -Dgles2=disabled `
  -Dllvm=disabled -Dxmlconfig=disabled -Dzstd=disabled -Dzlib=disabled `
  -Dshader-cache=disabled -Dbuild-tests=false
meson compile -C build
```

要点：

- `-Dgallium-drivers=zink` 只编 zink，减少体积与依赖；
- `-Dvulkan-drivers=` 为空，项目不需要 Mesa 自带的 Vulkan 驱动；
- `-Db_vscrt=mt` 静态链接 MSVC 运行库，保证只依赖系统 DLL；
- `-Dshader-cache=disabled` 是 meson 层面的默认关闭，实际缓存由补丁和 `ENABLE_SHADER_CACHE` 打开；
- `-Dllvm=disabled` 关闭 LLVM，避免额外依赖。

构建产物通常是：

```text
build/src/gallium/targets/libgl-gdi/opengl32.dll
build/src/gallium/targets/libgl-gdi/libEGL.dll
build/src/gallium/targets/libgl-gdi/libGLESv2.dll
```

以 meson 实际输出路径为准。

### 2.4 产物门禁

**每一项都必须通过**，否则不要部署：

```powershell
# 1. 导出数量与 ordinal base
dumpbin /exports opengl32.dll | Select-String "ordinal base"
# 期望：379 个导出，ordinal base 7

# 2. 只依赖系统 DLL
dumpbin /dependents opengl32.dll
# 期望：KERNEL32.dll、USER32.dll、GDI32.dll 等系统库；
#       不应出现 VCRUNTIME140.dll、MSVCP140.dll

# 3. 关键字符串
findstr /M /C:"vkCreatePipelineCache" opengl32.dll
findstr /M /C:"GALLIUM_DRIVER" opengl32.dll
findstr /M /C:"zink" opengl32.dll
```

运行期还要确认：

- 日志里 `GALLIUM_DRIVER` 至少出现 1 次；
- `zink` 关键字计数 ≥ 20；
- 没有 `LIBGL_ALWAYS_SOFTWARE` 回退；
- `zinkinfo` 打印的渲染器字符串包含 `zink`；
- 游戏能创建窗口并进入世界。

## 三、构建 Java mod

### 3.1 修正 JNA 路径

`zinkgl/build.gradle.kts` 和 `zinkinfo/build.gradle.kts` 目前引用本机绝对路径的 JNA jar：

```text
<MC目录>/libraries/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar
```

公开构建前改成 Maven 坐标：

```kotlin
dependencies {
    compileOnly("net.java.dev.jna:jna:5.14.0")
}
```

### 3.2 `zinkgl`

```powershell
cd <项目根目录>\zinkgl
gradle build
# 产物：build/libs/zinkgl-0.4.0.jar
```

构建配置：Java 21、NeoForge 21.1.229、NeoForge ModDev 2.0.141。

### 3.3 `zinkinfo`

```powershell
cd <项目根目录>\zinkinfo
gradle build
# 产物：build/libs/zinkinfo-0.3.0.jar
```

### 3.4 `createopt`

```powershell
cd <项目根目录>\createopt
gradle build
# 完整版：build/libs/createopt-1.1.0.jar

gradle build -Pprofile=lite
# 轻量版：createopt-zink-lite（排除 mixin 包与 createopt.mixins.json）
```

lite 版用于排查“问题是不是 mixin 注入导致的”，不含任何注入逻辑。

### 3.5 `dshcore` / `createlod` / `vkglshim`

这三个目录在公开快照中**只有源码，没有 Gradle 构建脚本**。需要自行搭建 NeoForge ModDev 工程：

1. 用 NeoForge 21.1.229 的 MDK（Mod 开发工具包）初始化工程；
2. 把 `src/`、`META-INF/neoforge.mods.toml`、mixin 配置复制进对应 source set；
3. `dshcore` 需要按 `dshcore-arfix.mixins.json`、`dshcore-chain.mixins.json`、`dshcore-op2r.mixins.json` 注册 mixin；
4. 构建出的 `dshcore` jar 含第三方代码，**仅限非商业自用**，不要公开分发。

## 四、部署到游戏实例

目标目录：`<MC目录>/versions/DSH-Zink/`（版本隔离实例）。

```text
<MC目录>/versions/DSH-Zink/
├── opengl32.dll          ← Mesa 产物（必需）
├── libEGL.dll            ← Mesa 产物
├── libGLESv2.dll         ← Mesa 产物
├── mods/                 ← 自行获取的 mod + 自建 mod
├── config/               ← cfgpack/config 下的模板
└── ...
```

步骤：

1. 把三个 DLL 放到版本目录根部，与版本 jar 同级；
2. 把 `cfgpack/config/` 下的模板复制到 `<MC目录>/versions/DSH-Zink/config/`（版本隔离实例的配置目录），再参考 `cfgpack/profiles/gpu-*.toml` 逐项调整；
3. 把构建好的 `zinkgl`、`zinkinfo`、`createopt` 以及自行获取的第三方 mod 放入 `mods/`；
4. 确认启动器的工作目录是实例根目录——Windows 的 DLL 搜索顺序中，应用目录优先于系统目录，这是替换 `opengl32.dll` 的前提；
5. 启动游戏，检查日志中的渲染器字符串与 zink 关键字。

**不要**把 `opengl32.dll` 复制到 `C:\Windows\System32`。那是系统级替换，会影响所有程序，且难以回滚。

## 五、配置要点

| 配置 | 推荐值 | 说明 |
|---|---|---|
| ImmediatelyFast `fast_buffer_upload` | `false` | E-A，默认开启，+4% ~ +8% |
| AcceleratedRendering `block_buffer_binding_restoring` | `DISABLED`（可选） | E-B，默认不开；开光影前必须过正确性门禁 |
| Flywheel backend | `flywheel:instancing` | 写成 `"INSTANCING"` 会回落到 indirect |
| 超分呈现后端 | `OPENGL` | `VULKAN` 仍属实验性，会与 zink 的 WSI 争同一窗口导致黑屏 |
| purescale 帧生成 | 关闭 | 实测净亏，仅实验档开启 |
| 电源 | 插电、高性能 | 电池态会触发 30 帧硬锁 |

配置文件为纯文本，可直接编辑；`cfgpack/profiles/` 下有按 GPU 厂商和光影开关预组合的版本。

## 六、常见问题

| 现象 | 可能原因 | 处理 |
|---|---|---|
| 启动即崩，找不到 `opengl32.dll` | DLL 不在实例根目录，或启动器工作目录不对 | 确认 DLL 与版本 jar 同级；检查启动器设置 |
| 能启动但仍是原生 GL | DLL 没被加载（系统目录优先），或 zink 初始化失败 | 用 `zinkinfo` 看渲染器字符串；检查 `GALLIUM_DRIVER` |
| 黑屏，但声音正常 | 超分呈现后端设成了 `VULKAN`，与 zink 的 WSI 争窗口 | 改回 `OPENGL` |
| 帧率锁 30 | 电池态 / Battery Boost | 插电，关闭省电模式 |
| 进世界卡死，日志停在 `nglGetTexImage` | 已知的 TACZ + AR 纹理回读问题（zoo14–23） | 换用 pgo2 基线 DLL；或关闭 AcceleratedRendering 纹理回读 |
| 补丁打不上 | `meson-fat-*.patch` 的 a 侧是 `.orig`；或源码版本不是 26.2.5 | 先复制 `.orig`；核对 Mesa 版本 |
| Gradle 找不到 JNA | `build.gradle.kts` 仍指向本机 `<MC目录>` | 改成 Maven 坐标 `net.java.dev.jna:jna:5.14.0` |
| 着色器每次启动都重编 | `ENABLE_SHADER_CACHE` 没在 7 个编译单元里打开 | 回到 2.2 第一组检查 |

## 七、版本对照

| 版本 | 内容 |
|---|---|
| cache-only `C768681A` | 仅 shader cache，17,421,824 字节 |
| zoo2 `BF71E736` | cache + P3，17,421,824 字节，379 导出 |
| pgo1 `494a96d1` | PGO 第一版 |
| pgo_ttl `d9f5cc50` | PGO + 缓存 TTL |
| **pgo2 `31e13c74`** | **v3.5.1 发货版本**，16,798,208 字节 |

PGO 版本的历史收益为 +8.2%，但后续基线已包含相关优化，最终 queue_final 队列**没有**使用 PGO；不要把它当作可叠加的额外收益。
