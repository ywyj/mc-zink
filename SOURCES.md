# 来源与可发布性说明（SOURCES）

本仓库是 **mc-zink** 项目的公开快照：只收录文档、自研源码、Mesa 补丁与配置模板。
不包含任何第三方二进制（mod jar、Mesa DLL、NVIDIA 组件、光影包）、反编译的 Minecraft 源码或本机日志。

- 生成方式：从作者本机工作目录按白名单复制，并清洗个人路径后提交。
- 复制日期：2026-11-03。
- 文件总数：190 个（复制 188 个 + 新增 `SOURCES.md`、`.gitignore`），约 768 KB，全部为文本（唯一非文本扩展名 `createopt-lite.marker` 实际也是文本）。

## 顶层内容与来源

| 仓库路径 | 来源（私有工作目录） | 内容 | 可发布理由 |
| --- | --- | --- | --- |
| `HANDOFF.md` | `HANDOFF.md` | 项目交接说明 | 自研项目文档，已清洗个人路径 |
| `STATUS.md` | `STATUS.md` | 各子任务状态与实验结论 | 自研项目文档，已清洗个人路径 |
| `ZINK_OPTIMIZATION_RESULTS.md` | 同名文件 | Zink 优化实验结果 | 自研项目文档，已清洗个人路径 |
| `PERF_STRATEGY_2026-10-19.md` | 同名文件 | 性能优化策略 | 自研项目文档，已清洗个人路径 |
| `ROADMAP_ADAPTIVE_2026-10-19.md` | 同名文件 | 自适应优化路线图 | 自研项目文档 |
| `RESUME.md` | 同名文件 | 中断后恢复工作的提示 | 自研项目文档，已清洗个人路径 |
| `REPORT_SR_VULKAN_PRESENT_2026-10-19.md` | 同名文件 | SR + Vulkan present 实验报告 | 自研项目文档，已清洗个人路径 |
| `mesaopt/` | `mesaopt/` | 3 份自研分析文档（`REPORT.md`、`WORKSPACE.md`、`ZINK_FPS_PATHS.md`）+ `patches/` 下的 20 个补丁、5 个 Python 脚本、zoo1/zoo2 补丁集与元数据 | 补丁是对 Mesa（MIT 许可）的自研修改，仅含 diff 与脚本，不含 Mesa 源码快照 |
| `zinkgl/` | `zinkgl/` | 自研 NeoForge mod：`src/` 7 个 Java 源文件 + 资源，`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties` | 自研 Java 源码 |
| `zinkinfo/` | `zinkinfo/` | 自研诊断 mod：`src/` 3 个 Java 源文件 + 资源，构建脚本 | 自研 Java 源码 |
| `dshcore/` | `dshcore/_src/cleanroom/` | 自研 NeoForge mod 源码 24 个文件（19 个 Java + mixin 配置 + 中英文语言文件），映射到 `dshcore/src/` | 自研 Java 源码；原目录其余部分为构建产物与测试证据，已排除 |
| `createlod/` | `createlod/` | `mod/src/` 9 个源文件 + 资源；`ALGORITHM.md`、`REPORT.md`、`STATUS-createlod.md` | 自研 Create LOD mod 与算法文档 |
| `createopt/` | `createopt/` | `src/` 11 个源文件与资源，`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties` | 自研 Create 优化 mod；已排除 `.ref/`、`build/`、`dist/`、`*.class` |
| `cfgpack/` | `cfgpack/` | `profiles/` 11 个配置档（GPU/iris/超分/环境变量）与 `config/` 26 个纯文本配置模板 | 自研配置模板，不含 jar/dll/zip |
| `vkglshim/` | `vkglshim/` | `src/` 40 个 Java 源文件、`res/META-INF/neoforge.mods.toml` | 自研 Java 项目；`classes/` 是编译产物，已排除 |
| `README.md`、`docs/` | 不经复制 | 发布说明、构建说明与法律声明 | 由发布流程单独撰写，不来自私有目录 |

## 已排除的内容（黑名单）

以下内容**不在**本仓库中，也不应提交：

- 任何 `.jar`、`.dll`、`.exe`、`.zip`、`.mrpack`、`.class` 二进制或分发包；
- 整合包（mrpack）中的第三方 mod、光影包、NVIDIA 组件与 Streamline SDK；
- Minecraft 反编译源码、`decomp/`、`.ref/` 参考代码；
- `mesaopt/patches/layers/states/`（约 60 MB 的 Mesa 源码工作快照）、`layers/.undo/`、`patches/.undo_ttl/`；
- `mesaopt/patches/zoo1/final/`（4 个修改后的 Mesa `.c` 源文件，属源码快照而非补丁）；
- `dshcore/` 下的 `backup/`、`build/`、`evidence/`、`install/`、`logs/`、`_build/`、`_inventory/`、`_test/` 与 `_src/*.jar`；
- `createopt/.ref/`、`createopt/build/`、`createopt/dist/`；
- `vkglshim/classes/`（40 个编译产物）与仓库根目录的 `vkglshim-0.1.0.jar`；
- 个人日志、PCL2 启动器目录下的任何文件；
- 补丁集中的 `__pycache__/` 与 `.pyc`。

## 清洗记录

复制后对全部文本文件做了正则清洗，共改写 22 个文件（其余文件不含需清洗内容）：

| 原内容 | 替换为 |
| --- | --- |
| 文档与命令里的 Windows 用户名段（用户目录路径中） | `<用户目录>` |
| 用户名本身（散落在文档与脚本中） | `<用户目录>` |
| 作者本机的工作区根目录（E 盘某目录） | `<项目根目录>` |
| 作者启动器目录下的 Minecraft 目录 | `<MC目录>` |
| 私网 IP（10/8、172.16/12、192.168/16） | `<内网地址>`（实际命中 0 处） |
| `.patch` 头中的作者本机绝对 diff 路径 | 归一化为相对路径 `a/src/...`、`b/src/...` |

未替换、且有意保留的内容：

- 系统工具路径（`C:\Windows\System32`、`C:/Python314`）——用于复现构建环境，不指向个人身份；
- `https://github.com/...`、`https://gitlab.freedesktop.org/...` 等上游链接；
- `%LOCALAPPDATA%` 等环境变量占位符。

## 已知限制

1. `zinkgl/build.gradle.kts` 与 `zinkinfo/build.gradle.kts` 原本引用作者本机启动器目录下的 JNA jar；清洗后为 `<MC目录>/libraries/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar`，构建前需改成自己的 Minecraft 目录，或改用 Maven 坐标 `net.java.dev.jna:jna:5.14.0`。
2. `meson-fat-opengl32.patch` 与 `meson-fat-wgl.patch` 的 a 侧文件名是 `meson.build.orig`（作者工作树中的快照副本）。在干净的 Mesa 源树上应用前，需先准备对应的 `.orig` 副本，或把 a 侧路径改为 `meson.build`。
3. `layer_ctl.py`、`zoo_apply.py`、`make_zoo_patches.py`、`apply_p5.py`、`verify_patch_hunks.py` 中的默认 Mesa 路径已替换为 `<项目根目录>` 占位符，运行前需按实际路径修改。
4. 文档记录的是 2026-10/11 的实验过程与数据，未在发布前重新复核；数字与结论以当时记录为准。

## 许可提示

- `mesaopt/patches/` 中的 diff 是对 Mesa 3D（MIT 许可）的修改。按 MIT 条款，分发时需保留 Mesa 的版权声明与许可文本；建议在 `README.md` 或 `docs/` 中附上上游许可说明。
- `zoo2/` 下的 `1ca365b2.patch`、`32658eec.patch`、`5710cae1.patch` 是 Mesa 上游提交（作者 Mike Blumenkrantz），保留原始 `From`/`Subject` 头与提交哈希，便于追溯。
- 其余自研代码目前**没有**显式开源许可证文件；正式发布前需由作者选定许可证（如 MIT/Apache-2.0）并补充 `LICENSE`。
