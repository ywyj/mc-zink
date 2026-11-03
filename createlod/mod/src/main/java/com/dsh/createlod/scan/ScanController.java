package com.dsh.createlod.scan;

import com.dsh.createlod.CreateLod;
import com.dsh.createlod.config.LodConfig;
import com.dsh.createlod.policy.PolicyEngine;
import dev.engine_room.flywheel.api.visualization.VisualManager;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Client tick scanner: keeps the LOD state in sync with the camera without event hooks.
 *
 * <p>Why a scanner instead of block/light events: the inputs to the decision are (a) neighbour block states,
 * (b) camera distance, (c) transient geometry. A periodic O(6..54) rescan of nearby block entities is far
 * cheaper than maintaining a dirty set, and it cannot miss a change (no event leaks). The cost is bounded by
 * chunk count and is measured into config/createlod-stats.txt.
 *
 * <p>This scanner is also the compatibility fallback: even if the Flywheel mixin fails to apply (e.g. a future
 * Flywheel version), the scanner still removes already-created visuals; only the "never create" optimisation is lost.
 */
public final class ScanController {
    private static ClientLevel lastLevel;
    private static int tickCounter;
    private static int scanCounter;
    private static int statsCounter;
    private static int scans;
    private static long scanNanosTotal;
    private static long lastScanNanos;
    private static int lastCulled;
    private static int lastRestored;
    private static int changeLogs;

    private ScanController() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        try {
            scan();
        } catch (Throwable t) {
            PolicyEngine.disable(t);
        }
    }

    private static void scan() {
        LodConfig cfg = LodConfig.get();
        if (!cfg.enabled || PolicyEngine.isDisabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.gameRenderer == null) {
            if (lastLevel != null) {
                PolicyEngine.clearMarks();
                lastLevel = null;
                CreateLod.LOG.info("[createlod] left level -> cull marks cleared");
            }
            return;
        }
        if (level != lastLevel) {
            PolicyEngine.clearMarks();
            lastLevel = level;
            CreateLod.LOG.info("[createlod] level changed -> cull marks cleared");
        }
        if (++tickCounter < cfg.rescanTicks) {
            return;
        }
        tickCounter = 0;

        VisualizationManager manager;
        try {
            manager = VisualizationManager.get(level);
        } catch (Throwable t) {
            // Some dimensions (e.g. Sable sub-worlds) may not support visualization; skip silently.
            return;
        }
        if (manager == null) {
            return;
        }
        VisualManager<BlockEntity> blockEntities = manager.blockEntities();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        int radius = cfg.scanRadiusChunks > 0
                ? cfg.scanRadiusChunks
                : Math.min(32, mc.options.getEffectiveRenderDistance() + 2);
        int centerChunkX = ((int) Math.floor(camera.x)) >> 4;
        int centerChunkZ = ((int) Math.floor(camera.z)) >> 4;

        long started = System.nanoTime();
        int culled = 0;
        int restored = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkAccess access = level.getChunkSource()
                        .getChunk(centerChunkX + dx, centerChunkZ + dz, ChunkStatus.FULL, false);
                if (!(access instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    boolean wasCulled = PolicyEngine.isMarkedCulled(blockEntity.getBlockPos());
                    boolean cull;
                    try {
                        cull = PolicyEngine.shouldCull(blockEntity, camera, wasCulled);
                    } catch (Throwable t) {
                        PolicyEngine.disable(t);
                        return;
                    }
                    if (cull != wasCulled) {
                        PolicyEngine.putMarked(blockEntity.getBlockPos(), cull);
                        if (cull) {
                            blockEntities.queueRemove(blockEntity);
                            culled++;
                            PolicyEngine.noteCulled();
                            logChange(cfg, "cull", blockEntity);
                        } else {
                            blockEntities.queueAdd(blockEntity);
                            restored++;
                            PolicyEngine.noteRestored();
                            logChange(cfg, "restore", blockEntity);
                        }
                    }
                }
            }
        }

        long elapsed = System.nanoTime() - started;
        lastScanNanos = elapsed;
        scanNanosTotal += elapsed;
        scans++;
        lastCulled = culled;
        lastRestored = restored;
        scanCounter++;
        if (statsCounter++ * Math.max(1, cfg.rescanTicks) >= cfg.statsIntervalTicks) {
            statsCounter = 0;
            writeStats(manager, radius, cfg);
        }
        if (cfg.debug && scanCounter % 100 == 0) {
            CreateLod.LOG.info("[createlod] scan #{} culled={} restored={} marked={} visuals={} scan_ms={}",
                    scans, culled, restored, PolicyEngine.markedCount(), blockEntities.visualCount(),
                    String.format(Locale.ROOT, "%.3f", elapsed / 1_000_000.0D));
        }
    }

    private static void logChange(LodConfig cfg, String action, BlockEntity blockEntity) {
        if (!(cfg.logChanges || cfg.debug) || changeLogs >= 200) {
            return;
        }
        changeLogs++;
        CreateLod.LOG.info("[createlod] {} {} at {} (change log {}/200)",
                action,
                PolicyEngine.blockId(blockEntity.getBlockState()),
                blockEntity.getBlockPos(),
                changeLogs);
    }

    /** Human- and benchmark-parseable snapshot; written every statsIntervalTicks while enabled. */
    private static void writeStats(VisualizationManager manager, int radius, LodConfig cfg) {
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve("createlod-stats.txt");
            int visuals;
            try {
                visuals = manager.blockEntities().visualCount();
            } catch (Throwable t) {
                visuals = -1;
            }
            StringBuilder sb = new StringBuilder(512);
            sb.append("# createlod stats (client side, auto-written)\n");
            sb.append("enabled=").append(cfg.enabled).append('\n');
            sb.append("disabled=").append(PolicyEngine.isDisabled()).append('\n');
            sb.append("disabled_reason=").append(PolicyEngine.disabledReason()).append('\n');
            sb.append("rescan_ticks=").append(cfg.rescanTicks).append('\n');
            sb.append("scan_radius_chunks=").append(radius).append('\n');
            sb.append("scans=").append(scans).append('\n');
            sb.append("scan_ms_last=").append(String.format(Locale.ROOT, "%.3f", lastScanNanos / 1_000_000.0D)).append('\n');
            sb.append("scan_ms_avg=").append(String.format(Locale.ROOT, "%.3f",
                    scans == 0 ? 0.0D : scanNanosTotal / 1_000_000.0D / scans)).append('\n');
            sb.append("culled_changed_last=").append(lastCulled).append('\n');
            sb.append("restored_changed_last=").append(lastRestored).append('\n');
            sb.append("culled_total=").append(PolicyEngine.culledTotal()).append('\n');
            sb.append("restored_total=").append(PolicyEngine.restoredTotal()).append('\n');
            sb.append("marked_culled_positions=").append(PolicyEngine.markedCount()).append('\n');
            sb.append("flywheel_visuals=").append(visuals).append('\n');
            sb.append("rules=selfCasing:").append(cfg.selfCasing)
              .append(",windowCovered:").append(cfg.windowCovered)
              .append(",encasedFar:").append(cfg.encasedFar).append('@').append(cfg.encasedLodDistance)
              .append(",buried:").append(cfg.buried)
              .append(",farLod:").append(cfg.farLod).append('@').append(cfg.farLodDistance).append('\n');
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Throwable t) {
            // stats are diagnostics only; never disable the feature because of them
        }
    }
}
