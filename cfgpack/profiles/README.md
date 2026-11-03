# profiles/ — SR 超分 & 帧生成 覆盖片段（overlay）

这些文件**不是**完整配置，只是 `config/super_resolution/config.toml` 的“最小覆盖片段”。
用法（三选一）：
1. 游戏内 **F6** 打开 Super Resolution 配置界面，按片段里的值逐项修改（推荐，写入会立刻生效）；
2. 退出游戏，编辑 `<gameDir>/config/super_resolution/config.toml`，把片段内同名键替换为片段值（保留其余键与注释）；
3. 等 dshconfig 更新：把片段键合并进 `cfgpack/config/super_resolution/config.toml` 后重新构建 jar。

| 文件 | 用途 | 关键差异 |
|---|---|---|
| `sr-nofg-stable.toml` | **默认稳定版**（zink 下 FG 不可用时）：纯超分 fsr1 + 1.5x + Reflex | `frame_generation.mode="OFF"` |
| `sr-fg-experimental.toml` | FG 通道修好后使用：X2 帧生成 + Streamline 后端 | `frame_generation.mode="X2"`、`backend="wisteria:streamline"` |

GPU 能力档（NVIDIA/AMD/Intel）与“有光影/无光影”组合见 `../SR_IRIS_NOTES.md`。

## 追加：GPU 档与“有光影/无光影”组合（v1.2.0）

| 文件 | 用途 | 关键差异 |
|---|---|---|
| `gpu-nvidia.toml` | NVIDIA 档（本机 RTX 3060 Laptop） | fsr1 1.5x、present VULKAN、FG OFF、Reflex ON |
| `gpu-amd.toml` | AMD 档 | fsr1 1.5x、FG OFF、**low_latency.mode="OFF"**（Reflex 仅 NVIDIA） |
| `gpu-intel.toml` | Intel 档（Arc / Iris Xe） | fsr1 1.5x、FG OFF、low_latency OFF；iGPU 可把 upscale_ratio 提到 2.0 |
| `iris-on.toml` | 有光影组合（Complementary/Photon） | unstable_incompatible_shader_support=true、compat_shader_compiler=true |
| `iris-off.toml` | 无光影组合 | unstable_incompatible_shader_support=false，其余同 |

> 算法可行性、两套组合的完整推荐与实测方法见 `../SR_IRIS_NOTES.md`。

## Vulkan 版本 / 兼容 zinkgl.env 档（对应 `<项目根目录>\cfgpack\VULKAN_COMPAT.md`）

| 文件 | 场景 | 关键键 |
|---|---|---|
| `vk-env-latest.env` | 新 loader/驱动（Vulkan 1.4，默认） | WSI=fifo_relaxed，缓存开启 |
| `vk-env-compat.env` | 老 Vulkan 1.3.x / 1.3.30x loader、relaxed present 抖动 | WSI=fifo，缓存开启，可选 ZINK_DEBUG=info |
| `vk-env-diagnostic.env` | 排障：打印 zink 看到的 Vulkan 设备/扩展 | WSI=fifo + ZINK_DEBUG=info |

用法：把对应文件内容拷成 `<gameDir>/zinkgl.env`（zinkgl 的 EnvLoader 支持 KEY=VALUE 覆盖/追加默认值），重启游戏；恢复默认=删除该文件。
注意：这三档只影响 Mesa/zink 环境变量，不改变 loader/驱动版本；**不要随包分发/替换 `vulkan-1.dll`**。

## zink 转译性能实验（对应 <项目根目录>\cfgpack\ZINK_TRANSLATION_PERF.md）

结论先行：GL 已是 4.6 上限（Mesa 26.2.5 最新稳定版），提 GL 版本无帧率收益；zink 已追平原生 OpenGL（142~150 / 88.2 / 90.8 vs 原生 147.6 / 84.9 / 88.9）。可调的是 zink 进程环境变量（ZINK_DESCRIPTORS auto/lazy/db、ZINK_DEBUG gpl/rp/norp、ZINK_INLINE_UNIFORMS），A/B 变体已放在 `bench/`（8 个 .bat + README），需 Lead 实测（先关 FG 再测渲染帧）。
