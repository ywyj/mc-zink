English | [中文](README.md)

# mc-zink: Running Minecraft on Mesa zink (OpenGL → Vulkan)

> **Bottom line:** Mesa zink runs Minecraft 1.21.1 (NeoForge), and in steady state it reaches about **0.88×** the frame rate of native NVIDIA OpenGL on the same machine (pooled median 182 vs 212 fps). It does **not** beat native OpenGL; the gap is roughly 12%. That number, and the specific calls the gap comes from, is what this project is about.

- Code license: **PolyForm Noncommercial 1.0.0** (modification allowed, any commercial use prohibited).
- Documentation license: **CC BY-NC-SA 4.0**.
- **This repository contains no third-party binaries**: no mod jars, shader packs, NVIDIA DLLs, game files, or decompiled sources. See [Section 8](#8-what-cannot-be-redistributed) for what is missing, why, and how to obtain it yourself.
- Not affiliated with or sponsored by Mojang, Microsoft, NVIDIA, Mesa, or Create.

---

## 1. What this project is

Three terms first:

- **OpenGL**: a cross-platform graphics API. Minecraft Java Edition's rendering code (the Blaze3D engine) uses it to draw blocks, entities, and UI.
- **Vulkan**: a newer, lower-level graphics API with explicit command buffers, memory, and synchronization. Lower driver overhead, but far more verbose.
- **zink**: a Gallium driver inside the Mesa open-source graphics stack. It **translates** OpenGL calls into Vulkan commands. It is not a Vulkan renderer; it is a translation layer.

On Windows, the project does three things:

1. **Builds zink as a drop-in `opengl32.dll`.** Minecraft loads this DLL from the instance directory at startup. The game still takes the OpenGL path, but every GL call enters Mesa's zink driver, which then submits Vulkan commands to the NVIDIA driver.
2. **Ships companion mods and tools** that work around hangs and readbacks on the zink path, and turns tunable settings into configuration templates.
3. **Quantifies every optimization with a reproducible measurement protocol** — including the many ideas that were measured and rejected. Rejected experiments matter as much as wins.

**It is not**: a rewrite of Minecraft into a native Vulkan renderer; an official Vulkan backend; an OptiFine replacement; or a change to the game's rendering architecture. Translation overhead is structural. The goal is to **shrink** it as far as possible, not to pretend it does not exist.

---

## 2. Why we built it

### Starting point: two low-end laptops and a Create server

This project did not start with a research question looking for a use case. It started with a concrete problem: I play the Create modpack with friends, and two of them run weak machines — one ultrabook, one Snapdragon laptop. The modpack's gears, shafts, production lines, and shaders brought both machines to their knees.

I wanted to see whether we could bypass Minecraft's default OpenGL path and raise the frame rate:

- use Mesa's zink to translate OpenGL calls into Vulkan;
- add **super resolution** (render at a lower internal resolution, then upscale to the target resolution);
- add **frame generation** (generate intermediate frames between real frames);
- and pair them with Sodium and other optimization mods to cut CPU-side rendering overhead.

The outcome split in two.

**What worked:** super resolution (FSR1, 1.5×) and frame generation both ran successfully. Sodium and Iris shaders also started normally, and the Vulkan presentation path was usable in the experimental configuration, with no black screen or deadlock.

**What fell short:** the zink translation layer itself is about 12% slower than native NVIDIA OpenGL on the same machine (0.88×). Part of what super resolution and frame generation saved was eaten by that structural overhead. I also wanted to use Vulkan's explicit features — multi-threaded command recording, finer-grained synchronization — to push CPU overhead down and frame rate up, but that part never materialized, and the project did not meet expectations.

So the honest status is: **the features work; the performance target was not reached.** I am publishing it in the hope that someone able to maintain it will pick it up — the code, the measurements, and the failure records are all here, and the path is laid this far.

### Technical motivations

Three motivations:

1. **Feasibility.** Minecraft's rendering load is dominated by many small draw calls, frequent state changes, and per-frame buffer uploads — exactly where a translation layer tends to lose. Can it run at all, and how well?
2. **A measured gap.** Not "it feels smooth", but a reproducible number, with the gap broken down to specific calls: how many `glBufferSubData` per frame, how many `glGetIntegeri_v`, how long a fence wait takes.
3. **Institutional memory.** Turn what works into configuration or Mesa patches, and record what does not work so the next person does not repeat it.

### Core results

Measurement protocol: 9 interleaved rounds, 1 Hz sampling, pooled median of the steady-state tail window. See [the measurement protocol](docs/MEASUREMENT.md).

| Queue | Pooled median (fps) | Relative to native OpenGL |
|---|---:|---:|
| zink + cache + E-A | **182** | **0.858** |
| zink + zoo1 + P3 + E-A | **188** | **0.887** |
| Native NVIDIA OpenGL + E-A | **212** | 1.000 |

Ratios computed per block of rounds are 0.808 / 0.910 / 0.854; the first block is visibly depressed by cold-start warm-up. The combined, published figure is:

> **zink ≈ 0.88 ± 0.03 of native OpenGL, a gap of about 12%.**

**Retired numbers:** the 0.63–0.71 figures in early notes, and the even earlier 0.555, came from different measurement protocols (occluded window, background throttling, battery downclocking) and are **not comparable** to the table above. This project only accepts data from the same protocol, the same power state, and a queue with a native control.

### Where the gap comes from

A steady-state frame issues about 2,168 GL calls: roughly 673 texture calls, 663 uniform calls, 481 bind calls, and 91 draws. The gap is not evenly distributed. A draw call costs about 1.75 µs, a bind about 289 ns, a uniform update about 192 ns — but multiplied by the per-frame counts, each becomes hundreds of microseconds. Another visible cost is a single `glClientWaitSync` per frame that waits without a timeout for a fence to complete, worth about 355 µs/frame (roughly 7.7% of wall clock). Both a deeper pipeline and a longer queue measured negative, however, so this is a symptom of CPU-throughput saturation, not dead time that can simply be reclaimed.

A separate 300-frame steady-state window measured **2,118.9 GL calls/frame**; Blaze3D accounts for **76.6%** of them. Different sampling windows give slightly different counts.

---

## 3. Architecture and components

### Data flow

```text
Minecraft 1.21.1 (Blaze3D rendering code)
        │  issues OpenGL calls (through LWJGL / JNA)
        ▼
opengl32.dll (Mesa 26.2.5 + this project's patches, single-file fat build, 379 exports)
        │  Gallium zink driver: translates GL state, draws, and uploads into Vulkan commands
        ▼
NVIDIA Vulkan driver (RTX 3060 Laptop, 596.21)
        │  kopper / WSI (window system integration) presents swapchain images to the window
        ▼
Screen
```

The key point: **the game has no idea Vulkan exists.** It still sees an OpenGL context; zink does the translation inside the DLL. As a result, every Java-side optimization (E-A, E-B) acts on "issue fewer or different GL calls", while the Mesa-side optimization (P3) acts on "wait less after translation".

### Components

| Component | Form | Responsibility | Status |
|---|---|---|---|
| Mesa DLLs (`opengl32.dll`, `libEGL.dll`, `libGLESv2.dll`) | Binaries; build them yourself | The zink driver itself; `opengl32.dll` is a single-file fat build | Based on Mesa 26.2.5 |
| `dshcore` | Mod source (jar not shipped) | AcceleratedRendering (AR) texture readback fix, 0Pack2Reload (op2r), chain mining, zsync, frame-time HUD | 1.3.1-zsync |
| `zinkgl` | Mod source | Injects zink environment variables at startup; GL / Streamline bridge | 0.4.0 |
| `zinkinfo` | Mod source | Prints GL / Vulkan diagnostics | 0.3.0 |
| `createopt` | Mod source | Culling for Create block entities, entities, and particles | 1.1.0 (including a lite variant) |
| `createlod` | Mod source | LOD for Create block entities | Built, not yet tested in-game |
| `cfgpack` | Configuration templates | 26 config files + 11 profiles (GPU vendor, shader on/off, upscaling tiers, Vulkan environment) | Plain text |
| `mesaopt` | Patches, scripts, build notes | Mesa patches, patch-layer management, build gates | 26.2.5 |
| `vkglshim` | Source (experimental) | A compatibility layer that emulates GL on top of Vulkan | No jar released |

`zsync` is a synchronization change inside `dshcore`: it turns AcceleratedRendering / Flywheel's synchronous polling into non-blocking `glClientWaitSync` probes, so the render thread does not spin waiting. It shipped with v3.5.1, but **whether it actually takes effect at runtime still needs in-game confirmation**.

---

## 4. Key optimizations and measurements

Three main lines of work. Every gain below comes from the same queue with a native control; protocol and confidence levels are in [the measurement protocol](docs/MEASUREMENT.md).

### E-A: ImmediatelyFast with `fast_buffer_upload` disabled

- **What:** set ImmediatelyFast's `fast_buffer_upload` to `false` (default since v3.2).
- **Why it helps:** when enabled, 76–81 `glBufferSubData` calls per frame go through Mesa's `u_upload` staging buffer and then an extra `vkCmdCopyBuffer`. When disabled, they take `glBufferData`'s `DISCARD_WHOLE_RESOURCE` whole-buffer reallocation fast path (`src/mesa/main/bufferobj.c:292-318`), saving one copy and one synchronization.
- **Data:** P1b measured +4.0% (raw +7.6%); P1 measured +21.5% after drift correction, but that value was contaminated and clearly exaggerated. Conservative range: **+4% to +8%**, which works out to about 0.73–0.77 ms/frame (76–81 calls × about 9.5 µs).
- **Corroboration:** with E-A active, the `glBufferSubData` sites in the GL call profile drop to zero, and `GlStateManager._glBufferData` rises to 68.24 calls/frame.

### E-B: AcceleratedRendering with buffer-binding restoration disabled

- **What:** set `[core_settings.block_buffer_binding_restoring].feature_status` to `"DISABLED"`.
- **Why it helps:** for safety, the feature restores buffer bindings every frame at a cost of about 89 `glGetIntegeri_v` queries (89.7 in the GLCOUNT profile). Disabling it drops those two profile sites (1275 / 1276) from 89 calls/frame to 0 and total calls from 2,168 to 2,055.
- **Data:** P1 +12.8%, P1b +4.5%; conservative range **+4% to +5%**, about 0.40 ms/frame.
- **Why it is not the default:** skipping state restoration makes correctness depend on the "raw" state happening to be right. The six-scene screenshot gate with shaders enabled (`shaders/eopt/SMOKE_SHADERON_CHECKLIST.md`, a local workspace file not included in the public snapshot) **did not pass**, so this stays an optional setting, not a default. Risk: medium.

### P3: remove the per-frame frontbuffer flush fence wait

- **What:** delete the per-frame `util_queue_fence_wait(&bs->flush_completed)` inside `zink_flush_frontbuffer()` (original code at `zink_screen.c:1937-1940`). Semantically equivalent to Mesa upstream commit `33f0fecc`.
- **Why it helps:** the patch is equivalent to upstream `33f0fecc`, and removing the wait measured positive, meaning the wait was not buying any observable benefit for the current frame.
- **Data:** +2.0% to +3.6% in the mix1 scene; adjacent-pair comparisons in queue_final were +7.7% / −2.6% / +6.6%, median +6.6%; the conservative published range is **+2% to +4%**.
- **Form:** measurements used zoo1's `MESA_ZINK_NO_FB_WAIT` switch (the B queue); the production form is the single-hunk patch under `mesaopt/patches/zoo2/`, **always on, with no environment-variable switch**. Artifact: `opengl32.zoo2.dll`, 17,421,824 bytes, 379 exports.

### Rejected approaches

A rejection is not a failure list; it is evidence that a road has already been walked. All results below are same-queue or same-scene A/B comparisons:

| Approach | Result |
|---|---|
| `ZINK_DEBUG=noopt` | −18% to −27% |
| `GALLIUM_THREAD=0` | −13% to −18% |
| `flushsync` | −6% to −14% |
| `ZINK_DESCRIPTORS=lazy` | No gain |
| `ZINK_DEBUG=rp` | No gain |
| kopper synchronous present (P1 / P4) | −1.5% to −4% |
| P2 batch window 5 → 12 | No gain |
| Mesa 26.3.0-rc1 whole tree | +0% to +2.5%, within noise, not adopted |
| H2b deeper pipeline | −2.8% |
| Sodium render-ahead 4 → 32 | −5% to −6% |
| glthread | −2.8% to −5.2% |
| LTO (link-time optimization) | +1.1% ± 1.2%, within noise |
| PGO (profile-guided optimization) | Historically +8.2%; absorbed into later baselines, not used in the final queue |

---

## 5. Quick start and build

This project **ships no prebuilt binaries**; you build Mesa and the mods yourself. Full steps are in [docs/BUILD.md](docs/BUILD.md); the materials checklist is in [docs/REPRODUCE.md](docs/REPRODUCE.md).

Shortest path:

1. Prepare Windows 10/11 x64, Visual Studio Build Tools (MSVC + Windows SDK), Meson, Ninja, Python 3.14, and JDK 21.
2. Download the Mesa 26.2.5 source tree and apply the patches under `mesaopt/patches/` in order (shader cache, fat single-file build, zoo2 P3).
3. Configure and build with meson to produce `opengl32.dll`, `libEGL.dll`, and `libGLESv2.dll`; verify the export table (379 exports, ordinal base 7, system DLLs only).
4. Build `zinkgl`, `zinkinfo`, and `createopt` (`dshcore` needs a project set up by hand), then place them and the config templates under `<MC directory>/versions/mc-zink/`.
5. Launch the game, confirm `GL_RENDERER` contains "zink", then measure using [docs/MEASUREMENT.md](docs/MEASUREMENT.md).

---

## 6. Measurement protocol

One sentence: **9 interleaved rounds, 1 Hz sampling, median of the last 30 steady-state frames per round, pooled median across rounds**; always with a native control, always recording power state; a single-round difference of ±5% to ±8% is noise.

The full protocol, per-round raw values, and the measurement design and confidence for E-A / E-B / P3 are in [docs/MEASUREMENT.md](docs/MEASUREMENT.md). Three rules matter most:

1. **Absolute values from different queues are not comparable.** Only compare ratios within the same interleaved queue.
2. **A speedup without a native control does not count.** Same scene, same time window, same launcher session.
3. **Power state must be recorded.** Battery mode triggers a hard 30 fps lock, and temperature/clock drift can make two runs of the same configuration differ by 7%–12%.

---

## 7. Limitations and risks

1. **Translation overhead is structural.** Every GL call must be translated, batched, and submitted. The gap is concentrated in a few heavy calls and internal waits and cannot be tuned away. The honest result is 0.88, not 1.0.
2. **The official Vulkan backend is still experimental.** The official Vulkan rendering backend shipped with the newer Minecraft line (26.2 and later) is still marked Experimental; this project has not validated against it and does not depend on it. This describes upstream status; confidence is medium, and it was not re-verified inside this repository.
3. **Shader packs change the bottleneck.** With shaders enabled the GPU becomes the bottleneck, and CPU-side gains (E-A / E-B / P3) are compressed. Every number in this project is **without shaders**.
4. **Candidate DLLs hang in a TACZ scene.** In the TACZ (gun mod) depot scene, during AcceleratedRendering texture readback, the zoo14–23 candidates block the render thread forever in `nglGetTexImage`; unresolved. v3.5.1 avoids the issue by using the pgo2 baseline, and the same world runs normally.
5. **The optimizations do not transfer to native Vulkan.** E-A changes the GL upload path, E-B changes GL state queries, and P3 changes zink's GL front-end fence. Switch to a native Vulkan rendering backend and all of them become irrelevant.
6. **Single machine, single GPU.** The test platform is a Ryzen 7 5800H + RTX 3060 Laptop (driver 596.21), 1080p, a superflat world, 56 mods. Do not extrapolate to other CPU/GPU/driver combinations.
7. **Open items:** `createlod` is built but not tested in-game; whether `zsync` takes effect at runtime is unconfirmed; the Vulkan present backend remains experimental (it contends with zink's WSI for the same window and causes a black screen, so OpenGL present is the default); the slight hitch when moving the mouse is unfixed.
8. **No warranty.** Provided "as is", with no promise of fitness for any purpose. Modifying drivers and replacing system DLLs is at your own risk.

---

## 8. What cannot be redistributed

This repository contains only **documentation, original source code, Mesa patches, and configuration templates**. The following are never shipped with the repository or attached to Releases. Full reasons, the license matrix, and alternatives are in [docs/LEGAL-BOUNDARIES.md](docs/LEGAL-BOUNDARIES.md).

### 8.1 Third-party mod jars (including this project's own `dshcore` build)

| File | What it is | Why it is not shipped | What breaks without it | How to obtain it |
|---|---|---|---|---|
| `alltheleaks-*.jar` | Memory-leak fix mod | MIT permits redistribution, but this project does not bundle third-party binaries, to avoid version drift and warranty exposure | Memory leaks accumulate during long sessions | Author pages on Modrinth / CurseForge |
| `colorwheel-*.jar` | Flywheel's instancing rendering backend | LGPLv3, with no full license text inside the jar; redistribution requires LGPL obligations (source / relinking rights) | Flywheel's instancing backend disappears and Create rendering falls back | Author's release page |
| `cupboard-*.jar` | Dependency library mod | All rights reserved (ARR); redistribution prohibited | Modpacks that depend on it fail at startup | Author's release page |
| `ocuwheel-*.jar` | OC / Flywheel compatibility mod | BRSSLA V2.0; no full license text in the repository, rights unclear | The OC–Flywheel compatibility layer is missing | Author's release page |
| `Opticores-*.jar` | Visual / shader compatibility mod (modified) | Modified MIT (dshfix); redistributing a modified build requires keeping notices | Visual / shader compatibility features are missing | Author's page, or patch it yourself under MIT |
| `sentrymechanicalarm-*.jar` | Mechanical-arm sentry mod | MIT | The sentry feature is missing | Author's release page |
| `gpumemleakfix-*.jar` | VRAM-leak fix mod (modified) | ARR, and the filename identifies it as a modified build | VRAM leak fix is missing | Get the original from the author |
| `lanserverproperties-*.jar` | LAN server properties mod | MIT | LAN property configuration is missing | Author's release page |
| `dshcore-1.3.1-zsync.jar` | This project's core mod (arfix, op2r, chain, zsync, HUD) | Original code plus embedded third-party code (EntityCulling, Protective License, non-commercial) creates a mixed license; cannot be distributed under PolyForm as a whole | AR readback fix, chain mining, op2r, and HUD all missing | Source is readable; set up a project and build it yourself (no Gradle script is included), for non-commercial personal use only |

### 8.2 Shader packs

| File | What it is | Why it is not shipped | What breaks without it | How to obtain it |
|---|---|---|---|---|
| `ComplementaryReimagined_r5.9.3.zip` | Shader pack | Complementary License Agreement 1.7 explicitly prohibits redistribution | Shaders unavailable | Author's website / Patreon release page |
| `photon-v1.3b.zip` | Shader pack | Its custom license allows redistribution but prohibits distribution on profitable platforms or for a fee; platform classification is risky | Shaders unavailable | Author's GitHub Releases |

### 8.3 NVIDIA proprietary DLLs

| File | What it is | Why it is not shipped | What breaks without it | How to obtain it |
|---|---|---|---|---|
| `nvngx_dlssg.dll` | DLSS Frame Generation runtime | NVIDIA proprietary component; the EULA prohibits bare redistribution | DLSS-G frame generation unavailable | Official driver / games that ship it |
| `sm86_backend.dll` | Backend runtime for a specific upscaler | Same as above, and the origin is unknown | That upscaler backend unavailable | Official driver |
| `version.dll` | Version proxy / hijack-style DLL | Origin and authorization both unknown; should not circulate | Version spoofing stops working | Not recommended |
| `libSuperResolution+win64+release.dll` and other `libSuperResolution*.dll` | NVIDIA Streamline / NGX upscaling runtime | Proprietary component; bare redistribution prohibited | Upscaling runtime missing | NVIDIA DLSS SDK / shipped with games |

### 8.4 Decompiled sources

| File | What it is | Why it is not shipped | What breaks without it | How to obtain it |
|---|---|---|---|---|
| Minecraft decompiled sources (`decomp/`, `.ref/`) | Decompiled Minecraft source | The Mojang EULA prohibits distributing game code | Cannot read Minecraft internals directly | Decompile a game jar you legally own, for personal study only |
| Create decompiled sources | Decompiled Create source | Create's own license plus Mojang mappings restrictions | Cannot read Create internals directly | Decompile it yourself, for personal study only |

### 8.5 Launcher

| File | What it is | Why it is not shipped | What breaks without it | How to obtain it |
|---|---|---|---|---|
| PCL2 launcher | A Minecraft launcher; its directory contains user configuration | Third-party software with an unverified redistribution license, and it contains user configuration | The launcher itself is missing | Official release page |

### 8.6 SDK

| File | What it is | Why it is not shipped | What breaks without it | How to obtain it |
|---|---|---|---|---|
| Streamline SDK | NVIDIA upscaling / frame-generation SDK | NVIDIA SDK license prohibits redistribution | Cannot compile upscaling / frame-generation components | NVIDIA developer site |

---

## 9. Disclaimer

1. **Unofficial.** This project has no affiliation, sponsorship, or endorsement relationship with Mojang Studios, Microsoft, NVIDIA, the Mesa project, Create, or its authors.
2. **Non-commercial.** This project does not sell anything, charge fees, run ads, or offer paid downloads; it is for study, research, and personal non-commercial use only. Being non-commercial does not excuse copyright infringement — it grants no right to redistribute other people's work.
3. **No game files.** It does not provide Minecraft, NeoForge, Create, or any of their commercial assets. You must purchase and use them under their respective end-user license agreements.
4. **No third-party binaries.** See Section 8; neither the repository nor Releases contain third-party jars, shader packs, DLLs, or decompiled sources.
5. **Trademarks.** Minecraft © Mojang Studios; NVIDIA, DLSS, and Streamline are trademarks of NVIDIA; Create and Flywheel belong to their authors. Names appear here only as descriptive references.
6. **License scope.** Code in this repository is licensed under PolyForm Noncommercial 1.0.0, documentation under CC BY-NC-SA 4.0, and Mesa patches remain MIT. Third-party components stay under their own licenses.
7. **No warranty.** The software is provided "as is", without any express or implied warranty. Replacing a graphics driver may cause crashes, save corruption, or data loss; you accept the risk.
8. **Takedown.** If a rights holder believes content in this repository infringes, please contact us through the repository Issues. Confirmed content will be removed within a reasonable time.

---

## 10. License

| Scope | License | You may | You may not |
|---|---|---|---|
| Project code and configuration templates (`zinkgl/`, `zinkinfo/`, `dshcore/`, `createopt/`, `createlod/`, `vkglshim/`, `cfgpack/`, scripts) | **PolyForm Noncommercial 1.0.0** (full text in [LICENSE](LICENSE)) | Use, copy, modify, create derivative works, distribute non-commercially | Any commercial use: paid modpacks, paid downloads, ad monetization, bundling into commercial products, for-profit hosted services |
| Documentation (`README.md`, `docs/`, per-directory notes) | **CC BY-NC-SA 4.0** (see [LICENSE-docs](LICENSE-docs)) | Copy, distribute, modify, adapt, with attribution, non-commercially, share-alike | Commercial use; removing attribution; using technical measures to restrict others' lawful use |
| `mesaopt/patches/` | **MIT** (upstream Mesa license) | Use, modify, distribute, sublicense, keeping copyright and license notices | Removing copyright notices; holding the authors liable for the consequences of use |

**Why `mesaopt/patches/` is an exception:** these patches modify Mesa, which is MIT-licensed, so they are derivative works of MIT code. Keeping MIT complies with the upstream license and makes it easier to merge useful patches back into Mesa. The PolyForm license at the repository root **does not cover** this directory.

**Notes:**

- PolyForm Noncommercial 1.0.0 is **not an OSI-defined open-source license**; GitHub will classify it as "Other". It permits modification and redistribution but prohibits commercial use — this is a deliberate choice.
- The `dshcore` build embeds third-party code and **cannot** be distributed under PolyForm as a whole; the repository publishes only the original source.
- Each mod's `META-INF/neoforge.mods.toml` still carries a legacy `license = "MIT"` field that disagrees with the root license; the root [LICENSE](LICENSE) governs, and these fields will be synchronized before the next release.

---

## Project status and maintenance

Status: **a personal hobby project — feature-verified, performance target missed, now paused.** The author does not have enough time to push it further. The code, data, measurement protocol, and failure records are preserved in full.

If you want to take over or contribute:

- start with [STATUS.md](STATUS.md) and [HANDOFF.md](HANDOFF.md) for current progress, known issues, and open work;
- the most valuable direction is cutting CPU-side overhead (see "Where the gap comes from" in Section 2 and the unfinished Vulkan multi-threading / synchronization ideas);
- open an Issue describing what you want to do, so effort is not duplicated;
- the code is under PolyForm Noncommercial 1.0.0; non-commercial use and modification are allowed.

---

## Documentation index

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Architecture, components, GL → zink → Vulkan data flow, directory responsibilities |
| [docs/MEASUREMENT.md](docs/MEASUREMENT.md) | Measurement protocol, per-round data, design and confidence for the three optimizations |
| [docs/LEGAL-BOUNDARIES.md](docs/LEGAL-BOUNDARIES.md) | Non-redistributable list, third-party license matrix, repository hygiene rules |
| [docs/BUILD.md](docs/BUILD.md) | Mesa patches and meson build, Gradle build, DLL placement |
| [docs/REPRODUCE.md](docs/REPRODUCE.md) | Reproduction steps, materials you must supply, acceptance checklist |
| [SOURCES.md](SOURCES.md) | File provenance and sanitization notes for the public snapshot |
| [HANDOFF.md](HANDOFF.md) | Overall project handoff record |
| [ZINK_OPTIMIZATION_RESULTS.md](ZINK_OPTIMIZATION_RESULTS.md) | Raw optimization measurement records |
| [STATUS.md](STATUS.md) | Status, performance profile, and known issues |
