# 提案：管线/着色器缓存的 TTL（按年龄）淘汰

## 背景
- 现有 Mesa 磁盘缓存（`src/util/disk_cache.c`）已有 **按大小 LRU 淘汰**：`disk_cache.c:512-516` 在写入超限时循环 `disk_cache_evict_lru_item`；`MESA_SHADER_CACHE_MAX_SIZE` 默认 1GB（`:317-318`）。
- multipart DB 层（`src/util/mesa_cache_db_multipart.c:114-119`）把每 part 的 size limit 设为 max/num_parts；`:161/193` 用 `mesa_cache_db_eviction_score` 选最该淘汰的 part。
- 缺：**久未使用的管线/着色器在容量未满时永远不会被清掉**（用户诉求：剔除太久没用的管线）。

## 方案（最小改动，两个可选层次）
### A. 启动时 TTL sweep（推荐，改动最小）
1. `src/util/disk_cache.c`：`disk_cache_create()` 成功打开 DB 后，新增 `disk_cache_sweep_expired(cache, ttl_days)`：遍历 multipart DB 条目，读 entry 的 last_used/时间戳，早于 `now - ttl` 的调用 `mesa_cache_db_multipart_entry_remove()`（已有 API，`disk_cache.c:401-415` 同款）。
2. 入口条件：env `MESA_SHADER_CACHE_TTL_DAYS`（默认 0=关闭，建议发行默认 30），解析仿照 `MESA_SHADER_CACHE_MAX_SIZE`（`disk_cache.c:275-311`）。
3. 需要 entry 时间戳：若 DB entry 无 last_used，**方案 A'** 用文件 mtime：`<cache_dir>` 下 multipart 数据文件按 mtime 判断（`<sys/stat.h>` 已 include，`disk_cache.c:50`）。
### B. 命中时更新 last_used（更精确，改动大）
- 在 `disk_cache_lookup` 命中路径写回时间戳（SQLite update 或 sidecar），并让 LRU/eviction_score 同时考虑 age。

## 验证
1. 单元/集成：构造过期与新鲜缓存目录，启动 → 仅过期项被删；`MESA_SHADER_CACHE_TTL_DAYS=0` 时行为不变。
2. 实机：连续 3 次进世界，第 1 次冷启动 ~69-75s，第 2/3 次命中（median fps +7% 级别），缓存目录大小稳定不发散；改 TTL=1 天跑两次确认旧文件被清。
3. 回归：`_probe/layer_postbuild.py` gate + `gl_smoke` 双环境 PASS。

## 备注
- 该补丁只动 Mesa util 层，不碰 zink，风险低；构建走 `build/base` 增量（约 2-3 个 TU + 链接）。
- “越玩越流畅”的主体（管线缓存持久化）已在 zoo2 里；本补丁只是补上“清理久未使用”的一半。