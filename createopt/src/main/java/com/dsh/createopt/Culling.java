package com.dsh.createopt;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * 纯逻辑可见性判断：世界坐标点之间的体素射线 + 结果缓存 + 预算限流。
 * 不依赖任何 OpenGL 调用，所以在 Sodium/Iris、zink、原生 GL 下都安全。
 */
public final class Culling {
    private Culling() {}

    private static long windowStartNs = 0L;
    private static int entityTokens = 0;
    private static int blockEntityTokens = 0;

    /** entity id -> {timeMs, occluded(0/1)} */
    private static final Map<Integer, long[]> ENTITY_CACHE = new HashMap<>();
    /** BlockPos.asLong -> {timeMs, occluded(0/1)} */
    private static final Map<Long, long[]> BLOCK_CACHE = new HashMap<>();

    /**
     * boss 判定：1.21.1 没有 vanilla 的 EntityTypeTags.BOSSES，所以用具体类型 + 高血量兜底。
     * 宁可多保留（不剔除）也不能把 boss 剔掉。
     */
    public static boolean isBoss(Entity entity) {
        if (entity instanceof EnderDragon || entity instanceof WitherBoss || entity instanceof Warden) return true;
        return entity instanceof LivingEntity living && living.getMaxHealth() >= 100.0F;
    }

    private static void resetBudgetWindowIfNeeded(CreateOpt.Settings s) {
        long now = System.nanoTime();
        if (now - windowStartNs > 16_000_000L) {
            windowStartNs = now;
            entityTokens = Math.max(0, s.entityOcclusionBudget);
            blockEntityTokens = Math.max(0, s.blockEntityOcclusionBudget);
        }
    }

    private static boolean takeToken(boolean isEntity) {
        if (isEntity) {
            if (entityTokens <= 0) return false;
            entityTokens--;
            return true;
        }
        if (blockEntityTokens <= 0) return false;
        blockEntityTokens--;
        return true;
    }

    /** 实体遮挡判断（entity id 作为缓存键） */
    public static boolean isEntityOccluded(int entityId, Vec3 from, Vec3 to) {
        CreateOpt.Settings s = CreateOpt.SETTINGS;
        if (!s.occlusion) return false;
        Level level = Minecraft.getInstance().level;
        if (level == null) return false;
        double dist = from.distanceTo(to);
        if (!inOcclusionRange(dist, s)) return false;

        long nowMs = System.currentTimeMillis();
        long[] cached = ENTITY_CACHE.get(entityId);
        if (cached != null && nowMs - cached[0] < s.occlusionCacheMs) return cached[1] != 0L;

        resetBudgetWindowIfNeeded(s);
        if (!takeToken(true)) return cached != null && cached[1] != 0L;

        boolean occluded = raycast(level, from, to, s.occlusionStep, null);
        if (ENTITY_CACHE.size() > 2048) ENTITY_CACHE.clear();
        ENTITY_CACHE.put(entityId, new long[]{nowMs, occluded ? 1L : 0L});
        if (occluded) CreateOpt.debugLog("[CreateOpt] occluded entity id={} at {} blocks", entityId, String.format("%.1f", dist));
        return occluded;
    }

    /** 方块实体遮挡判断（BlockPos 作为缓存键；射线跳过目标方块本身，避免"自己的方块挡住自己"的误判） */
    public static boolean isBlockEntityOccluded(BlockPos pos, Vec3 from, Vec3 to) {
        CreateOpt.Settings s = CreateOpt.SETTINGS;
        if (!s.occlusion) return false;
        Level level = Minecraft.getInstance().level;
        if (level == null) return false;
        double dist = from.distanceTo(to);
        if (!inOcclusionRange(dist, s)) return false;

        long key = pos.asLong();
        long nowMs = System.currentTimeMillis();
        long[] cached = BLOCK_CACHE.get(key);
        if (cached != null && nowMs - cached[0] < s.occlusionCacheMs) return cached[1] != 0L;

        resetBudgetWindowIfNeeded(s);
        if (!takeToken(false)) return cached != null && cached[1] != 0L;

        boolean occluded = raycast(level, from, to, s.occlusionStep, pos);
        if (BLOCK_CACHE.size() > 2048) BLOCK_CACHE.clear();
        BLOCK_CACHE.put(key, new long[]{nowMs, occluded ? 1L : 0L});
        if (occluded) CreateOpt.debugLog("[CreateOpt] occluded block-entity at {} at {} blocks", pos, String.format("%.1f", dist));
        return occluded;
    }

    private static boolean inOcclusionRange(double dist, CreateOpt.Settings s) {
        return dist >= s.occlusionMinDistance && dist <= s.occlusionMaxDistance;
    }

    private static boolean raycast(Level level, Vec3 from, Vec3 to, double step, BlockPos skip) {
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len < 0.5D) return false;
        if (step < 0.05D) step = 0.05D;
        if (step > 4.0D) step = 4.0D;
        Vec3 dir = delta.scale(step / len);
        Vec3 p = from.add(dir);
        double travelled = step;
        int guard = 0;
        int maxSteps = (int) (len / step) + 2;
        while (travelled < len - 1.0D && guard++ < maxSteps) {
            BlockPos pos = BlockPos.containing(p.x, p.y, p.z);
            if (skip != null && skip.equals(pos)) {
                p = p.add(dir);
                travelled += step;
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.canOcclude()) return true;
            p = p.add(dir);
            travelled += step;
        }
        return false;
    }
}
