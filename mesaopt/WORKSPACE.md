# mesaopt 工作区（VSCode 稳定版）

## 目录
- `src/mesa-26.2.5/` — Mesa 26.2.5 源码（含我们全部补丁，layer 由 `patches/layers/layer_ctl.py` 管理）
- `build/base` `build/lto` `build/pgo` — 三套已配置的构建目录（base=zoo2 系列、lto=LTO、pgo=PGO 插桩/优化）
- `patches/` — 补丁与层：`*.patch`、`layers/`、`.undo_ttl/`（TTL 补丁前的原文备份）
- `dist/` — 产物：`zoo2/`（发货基线）、`pgo1/`（PGO）、`pgo_ttl/`（PGO+管线缓存 TTL）、`z0*` 诊断 DLL
- `_probe/` — 本地冒烟/门禁工具（`layer_postbuild.py`、`gl_smoke.exe`）

## VSCode 一键任务（Ctrl+Shift+B 或 Terminal > Run Task）
`mesa: build zoo2 base` / `setup PGO tree` / `build PGO instrument` / `collect profile` / `PGO optimize link` / `build PGO+TTL` / `gate dist/pgo_ttl` / `abi clean`

## 环境要点
- 所有 .bat 自带 `vcvars64`/ninja/python 路径（`C:/Python314`、`_tools/ninja`），在 VSCode 终端直接跑即可。
- 采集 PGO profile 时必须把 `…VC/Tools/MSVC/14.51.36231/bin/Hostx64/x64` 加进 PATH（提供 `pgort140.dll`），否则 ZinkGL 预加载失败会静默回退原生 GL；`.pgc` 会落在实例的 `mesa/` 目录（不是 `/PGD` 目录）。
- 头文件改动后先跑 `abi clean` 再构建（ninja 头依赖在本环境不可靠，见 `notes/LAYER_BUILD_CONSISTENCY_INCIDENT.md`）。

## 常用命令行
```bat
build\setup_pgo.bat reconfig
build\build_pgo_instrument.bat
build\pgo_optimize.bat
build\build_pgo_ttl.bat
C:\Python314\python.exe _probe\layer_postbuild.py dist\pgo_ttl\opengl32.pgo-ttl.dll pgoopt dist\pgo_ttl
```

## 当前发货件
- `dist/pgo_ttl/opengl32.pgo-ttl.dll` 16,785,408B sha256 `d9f5cc50511f7248…`（PGO + Win32 管线缓存 TTL，gate 37 PASS/1 尺寸误报）
- mrpack：`<项目根目录>/cfgpack/dist/mc-zink-340*.mrpack`（v3.4，含该 DLL + AR E-B 默认 DISABLED）