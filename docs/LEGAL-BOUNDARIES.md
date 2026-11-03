# 法律边界与不可分发清单

本文说明：哪些内容可以随仓库公开、哪些绝对不能、为什么，以及用户如何合法取得缺失的部分。它不是法律意见；涉及具体分发场景时请咨询律师。

## 一、基本原则

1. **仓库只放四类内容**：文档、自研源码、Mesa 补丁、配置模板。
2. **不打包任何第三方二进制**：mod jar、光影包、NVIDIA DLL、游戏本体、反编译源码、启动器、SDK，一律不进仓库，也不进 Release 附件。
3. **许可证允许再分发，不等于必须再分发**。MIT / LGPL 类组件即使可以再分发，本项目也选择不打包，避免版本漂移、担保责任和体积膨胀；用户应从上游获取。
4. **非盈利不豁免版权**。免费发布、不收费、不接广告，都不能成为分发他人受版权保护内容的理由。
5. **Release 附件同样是再分发**。把 jar 放进 Release、网盘或仓库内的直链，法律性质与放进仓库相同。

## 二、不可分发清单

### 2.1 第三方 mod jar（9 个）

| 文件名 | 许可证 / 权利状态 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| `alltheleaks-*.jar` | MIT | 许可允许再分发；本项目统一不打包第三方二进制 | 内存泄漏修复缺失，长时间游玩内存上涨 | Modrinth / CurseForge 作者页 |
| `colorwheel-*.jar` | LGPLv3（GNU 宽通用公共许可证第 3 版；jar 内未附许可全文） | 再分发需履行 LGPL 义务（提供源码/替换权），且版本易漂移 | Flywheel 的 instancing 后端缺失，Create 渲染回退 | 作者发布页；若再分发需自行满足 LGPL |
| `cupboard-*.jar` | 作者保留所有权利（ARR） | 明确禁止再分发 | 依赖它的整合包启动报错 | 作者发布页 |
| `ocuwheel-*.jar` | BRSSLA V2.0（仓库内无许可全文） | 权利不明，无法确认再分发权 | OC 与 Flywheel 的兼容层缺失 | 作者发布页 |
| `Opticores-*.jar` | MIT 修改版（dshfix） | 修改版再分发需保留版权与修改声明；本项目不打包 | 视觉/光影兼容项缺失 | 作者页；或按 MIT 自行修改 |
| `sentrymechanicalarm-*.jar` | MIT | 本项目统一不打包第三方二进制 | 机械臂哨兵功能缺失 | 作者发布页 |
| `gpumemleakfix-*.jar` | ARR，且文件名表明是修改版 | ARR 禁止再分发；修改版更无权分发 | 显存泄漏修复缺失 | 作者页获取原版 |
| `lanserverproperties-*.jar` | MIT | 本项目统一不打包第三方二进制 | 局域网属性配置缺失 | 作者发布页 |
| `dshcore-1.3.1-zsync.jar` | 自研代码 + 内嵌第三方代码（EntityCulling，Protective License 禁商用）→ 混合许可 | 不能整体按 PolyForm 分发；二进制内含第三方实现 | AR 回读修复、连锁、op2r、HUD 全部缺失 | 源码可读；自行搭建工程构建（仓库未附 Gradle 脚本），仅限非商业自用 |

**关于 `dshcore` 的特殊说明**：

- 仓库公开的是**自研源码**（`dshcore/src/`），不包含内嵌的第三方实现；
- 构建出的 jar 是混合许可产物，**不能**整体套用 PolyForm Noncommercial 1.0.0；
- 内嵌的 EntityCulling 代码适用 tr7zw 的 Protective License，禁止商业用途；
- 因此：源码可读、可改、可按 PolyForm 非商业使用；jar 不分发。

### 2.2 光影包（2 个）

| 文件名 | 许可证 / 权利状态 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| `ComplementaryReimagined_r5.9.3.zip` | Complementary License Agreement 1.7 | 明确禁止再分发 | 光影不可用 | 作者官网 / Patreon 发布页 |
| `photon-v1.3b.zip` | © 2021–2025 Benjamin Stott，自定义许可 | 允许再分发，但禁止在可获利平台分发或收费；平台定性有风险，故不打包 | 光影不可用 | 作者 GitHub Releases |

### 2.3 NVIDIA 专有 DLL

| 文件名 | 权利状态 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| `nvngx_dlssg.dll` | NVIDIA 专有，EULA（最终用户许可协议）禁止裸再分发 | 不能随仓库/Release 分发 | DLSS-G 帧生成不可用 | 官方驱动 / 支持的游戏自带 |
| `sm86_backend.dll` | NVIDIA 专有，来源不明 | 同上 | 特定超分后端不可用 | 官方驱动 |
| `version.dll` | 代理/劫持型 DLL，来源不明 | 来源与授权均不明，不应传播 | 版本伪装失效 | 不建议获取 |
| `libSuperResolution+win64+release.dll` 及同系列 `libSuperResolution*.dll` | NVIDIA Streamline / NGX 运行时，专有 | 不能裸再分发 | 超分运行时缺失 | NVIDIA DLSS SDK / 游戏自带 |

### 2.4 反编译源码

| 文件名 / 目录 | 权利状态 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| Minecraft 反编译源码（`decomp/`、`.ref/`） | Mojang EULA 禁止分发游戏代码 | 公开即侵权 | 无法直接阅读 MC 实现 | 用合法拥有的游戏 jar 自行反编译，仅供个人学习 |
| Create 反编译源码 | Create 自身许可 + Mojang 映射限制 | 同上 | 无法直接阅读 Create 实现 | 自行反编译，仅供个人学习 |

### 2.5 启动器

| 文件名 | 权利状态 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| PCL2 | 第三方软件，未核实再分发许可；目录含用户配置 | 授权不明 + 隐私风险 | 启动器功能缺失 | 官方发布页 |

### 2.6 SDK

| 文件名 | 权利状态 | 为什么不发 | 缺了影响什么 | 如何自行获取 |
|---|---|---|---|---|
| Streamline SDK | NVIDIA SDK 许可，禁止再分发 | 不能随仓库分发 | 无法编译超分/帧生成相关组件 | NVIDIA 开发者站点 |

## 三、第三方许可矩阵

| 组件 | 许可证 | 是否允许再分发 | 本仓库处理 |
|---|---|---|---|
| Mesa 26.2.5 源码 | MIT | 是，需保留版权声明与许可全文 | 只发补丁，不发源码/二进制 |
| `mesaopt/patches/`（本项目对 Mesa 的修改） | MIT（Mesa 衍生） | 是，需保留声明 | 随仓库分发，保留上游版权与提交信息 |
| 本项目自研代码与配置模板 | PolyForm Noncommercial 1.0.0 | 是，仅限非商业 | 根目录 `LICENSE` |
| 本项目文档 | CC BY-NC-SA 4.0 | 是，需署名/非商业/相同方式共享 | `LICENSE-docs` |
| alltheleaks | MIT | 是 | 不分发 |
| colorwheel | LGPLv3 | 是，需履行 LGPL | 不分发 |
| cupboard | ARR | 否 | 不分发 |
| ocuwheel | BRSSLA V2.0（无全文） | 未知 | 不分发 |
| Opticores | MIT（修改版） | 是，需保留声明 | 不分发 |
| sentrymechanicalarm | MIT | 是 | 不分发 |
| gpumemleakfix | ARR（修改版） | 否 | 不分发 |
| lanserverproperties | MIT | 是 | 不分发 |
| dshcore jar | 混合（自研 + EntityCulling Protective License） | 否（整体） | 只发自研源码 |
| ComplementaryReimagined | Complementary License 1.7 | 否 | 不分发 |
| photon | 自定义（禁止获利平台分发/收费） | 有条件 | 不分发 |
| NVIDIA DLL / Streamline SDK | NVIDIA 专有 EULA | 否 | 不分发 |
| Minecraft 源码 / 资源 | Mojang EULA | 否 | 不分发 |
| Create 源码 | Create 许可 + Mojang 映射 | 否 | 不分发 |
| PCL2 | 未核实 | 未知 | 不分发 |

**为什么 MIT 组件也不发**：许可证允许，但本项目不是整合包分发渠道。不打包可以避免：版本过期、构建来源不可信、用户以为“官方认证”、以及为第三方代码承担担保责任。用户从上游获取，能拿到最新版和官方校验值。

## 四、仓库卫生规则

发布前和发布后都要遵守：

1. **git 历史里不能有 jar / dll / zip / class**。如果历史提交里出现过，必须重写历史（filter-repo）后再公开；只删当前文件不够。
2. **Release 附件同样受限**。不能放第三方二进制、整合包 zip、含 NeoForge 版 MC jar 的完整包。
3. **不要在仓库里给镜像直链**。指向第三方二进制的直接下载链接，可能被认定为帮助侵权；最多链接到作者发布页。
4. **不要提交日志与个人路径**。公开快照已把用户名、工作区根、启动器目录替换为 `<用户目录>`、`<项目根目录>`、`<MC目录>`；私网 IP 为 0 命中。
5. **不要提交反编译产物**。`decomp/`、`.ref/` 已在黑名单中。
6. **保留上游版权信息**。Mesa 补丁头部的版权行、`zoo2/1ca365b2.patch` 等三个上游提交的 `From` / `Subject` / hash 必须保留。

## 五、已知的许可不一致

| 问题 | 现状 | 处理 |
|---|---|---|
| 5 个 mod 的 `neoforge.mods.toml` 写 `license = "MIT"` | 与根目录 PolyForm NC 不一致 | 以根目录 `LICENSE` 为准；后续发布前统一改为 PolyForm NC 或删除该字段 |
| `dshcore` 元数据写 MIT | 实际是混合许可 | 不再分发 jar；源码根目录以 PolyForm 为准，内嵌部分按原许可 |
| `colorwheel` jar 内无 LGPL 全文 | 再分发义务不清晰 | 不打包 |
| `ocuwheel` 无许可全文 | 权利不明 | 不打包 |
| 上游 Mesa `NOTICE` 缺具体版权人行 | 只有项目级声明 | 补丁中保留 Mesa 版权行；发布二进制时需附完整 MIT 文本 |

## 六、下架与联系

- 若权利人认为本仓库内容侵犯其权利，请通过仓库 **Issue** 联系，说明具体文件与权利依据；
- 确认后会在合理时间内删除或替换相关内容；
- 不提供私人邮箱，避免公开快照中出现个人信息；
- 对第三方 mod / 光影的名称引用仅作描述性指代，不构成隶属、赞助或背书。

## 七、免责声明（与 README 一致）

1. 与 Mojang Studios、Microsoft、NVIDIA、Mesa、Create 及其作者无隶属、赞助或背书关系。
2. 不销售、不收费、不接广告、不做付费下载；仅供学习、研究与个人非商业使用。
3. 不提供 Minecraft、NeoForge、Create 或任何商业素材；使用需自行购买并遵守各自 EULA。
4. 不提供任何第三方二进制；清单见第二章。
5. 商标归各自权利人；文中出现仅作描述性指代。
6. 代码按 PolyForm Noncommercial 1.0.0，文档按 CC BY-NC-SA 4.0，Mesa 补丁按 MIT；第三方组件按其自身许可。
7. 按“现状”提供，无任何明示或默示担保；替换图形驱动可能导致崩溃、存档损坏或数据丢失。
8. 侵权投诉通过 Issue 联系，确认后及时删除。

## 八、参考

- 完整法律审查记录：`research/tmp/legal-review.md`（本机工作区文件，未随公开快照；其中 §7 是本文免责声明的来源）
- 公开快照的文件来源与清洗说明：[SOURCES.md](../SOURCES.md)
- Mesa 许可：<https://docs.mesa3d.org/license.html>
- PolyForm Noncommercial 1.0.0：<https://polyformproject.org/licenses/noncommercial/1.0.0/>
- CC BY-NC-SA 4.0：<https://creativecommons.org/licenses/by-nc-sa/4.0/>
