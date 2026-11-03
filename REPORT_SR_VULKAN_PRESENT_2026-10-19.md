# SR / Mesa / Opticores 修复件实机验证报告 — 2026-10-19

作者：Lead。机器：AMD Ryzen 7 5800H + NVIDIA RTX 3060 Laptop（AC 供电，P0），Mesa 26.2.5 + zink Vulkan 1.4，1080p 1080x1920 前台。

## 1. SR VULKAN present 修复件（stability/srfix dshfix1）
- 证据（logs/latest.log，08:47-08:48 那次运行）：
  `Created Vulkan presentation swapchain: 1920x1080, images=3, presentMode=IMMEDIATE (requested=3, min=2, max=8, plannedGeneratedFrames=0)`
- 无 CaptureFrameRing 死锁、无 IllegalStateException、无黑屏、无崩溃；FG 协商 fg=wisteria:ngx / lowLatency=superresolution:reflex_vk。
- 结论：Vulkan 呈现通道可用（m00464 技术解锁），但默认仍用 OPENGL present。

## 2. present backend A/B（同配置、no shader / Complementary 各一轮，needSamples 26、cvMax 0.5、n=27-28、cv≈45-49%）
| 变体 | median fps | avg | first(sample) |
|---|---|---|---|
| SR FSR1 1.5x, no shader, VULKAN present | 184 | 147.3 | 63s |
| SR FSR1 1.5x, no shader, OPENGL present | 198 | 157.3 | 54s |
| SR FSR1 1.5x, Complementary, VULKAN present | 106 | 83.6 | 69s |
| SR FSR1 1.5x, Complementary, OPENGL present | 94 | 76.5 | 60s |
参考基线（无 SR 超分、OPENGL present）：noshader 189-208；Complementary 89-111。
解读：Vulkan present 与 OPENGL present 差异（-7%/+13%）在 cv≈45% 的噪声内，且启动慢 ~9s；SR FSR1 1.5x 在本机近似中性（CPU 受限时无收益、GPU 受限时有收益）。

## 3. Opticores dshfix（Sodium 0.8.13 mixin 描述符修复）
- 原问题：`InvalidInjectionException Invalid descriptor on opticore.client.mixins.json:sodium.MixinSodiumBlockRenderer ... Expected (BakedModel, BlockState, BlockPos, BlockPos, CallbackInfo) but found (BlockAndTintGetter, ..., CallbackInfoReturnable)`。
- 修复后运行（08:53-08:56）：latest.log 中 InvalidInjectionException / MixinSodiumBlockRenderer 命中 0 条。

## 4. Mesa 磁盘 shader 缓存 DLL（mesaopt 自编）
- patched：17,421,824 B sha256 C768681A31293E7F600742A70FF2B09C1D039A5DC29364504D6AAE9D15CC3A9F（ENABLE_SHADER_CACHE：GLSL->NIR + VkPipelineCache）。
- orig：17,456,128 B sha256 4CE35C57...（备份 <项目根目录>/_stage/mesa_backup_orig.dll）。
- A/B noshader：patched median 204 vs orig 208（无回归，噪声内）。
- 缓存命中：<项目根目录>/mesaopt/cache 524 文件/14MB -> 552/18MB（+28）；首帧 75s -> 69s（省 ~6s）。

## 5. 实例当前 verified 状态（2026-10-19 09:0x）
| 组件 | 文件 | 大小/sha 前缀 |
|---|---|---|
| dshcore | mods/dshcore-1.3.0.jar | 2,548,186 B / beedab08 |
| SR | mods/super_resolution-0.9.2-alpha.2+dshfix1.jar | 33,461,192 B / 55951443 |
| Opticores | mods/Opticores-1.1.0-dshfix+1.21.1.jar | 99,687 B / 4283729B |
| Mesa | mesa/opengl32.dll | 17421824 B / C768681A31293E7F |
mods 共 56 jar；SR config：backend=OPENGL、upscale=false、ratio=1.5、algo=fsr1、FG=OFF、irisExtension=false；iris enableShaders=false。

## 6. 增量修复包
<项目根目录>/cfgpack/dist/DSH-Zink-fixes-v3.1.0.zip（39,392,896 B，sha256 5000ADF10A03BD03...，6 条目：README/install.ps1/dshcore/SR fix/Opticores fix/mesa dll）。
