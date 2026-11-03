package com.dsh.createlod.policy;

import com.dsh.createlod.CreateLod;
import com.dsh.createlod.config.LodConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure decision logic: given a BlockEntity and the camera position, should its Flywheel visual be suppressed?
 *
 * <p>This class does not touch Flywheel types at all, so the mixin (which does) can call it and the scanner can
 * share the exact same decision. Every failure path disables the feature instead of throwing into Flywheel.
 */
public final class PolicyEngine {
    private static final Map<Block, String> ID_CACHE = new IdentityHashMap<>();
    /** Positions whose visual is currently suppressed by the scanner (drives distance hysteresis in createRaw). */
    private static final Long2ByteOpenHashMap MARKED = new Long2ByteOpenHashMap();

    private static volatile boolean disabled;
    private static volatile String disabledReason = "";
    private static volatile long culledTotal;
    private static volatile long restoredTotal;

    public static boolean isDisabled() {
        return disabled;
    }

    public static String disabledReason() {
        return disabledReason;
    }

    public static void disable(String reason) {
        if (disabled) {
            return;
        }
        disabled = true;
        disabledReason = reason;
        CreateLod.LOG.error("[createlod] feature disabled for this session: {}", reason);
    }

    public static void disable(Throwable t) {
        if (disabled) {
            return;
        }
        disabled = true;
        disabledReason = t.toString();
        CreateLod.LOG.error("[createlod] feature disabled after exception", t);
    }

    public static long culledTotal() {
        return culledTotal;
    }

    public static long restoredTotal() {
        return restoredTotal;
    }

    public static void noteCulled() {
        culledTotal++;
    }

    public static void noteRestored() {
        restoredTotal++;
    }

    public static boolean isMarkedCulled(BlockPos pos) {
        return pos != null && MARKED.get(pos.asLong()) != 0;
    }

    /** @return previous marked state */
    public static boolean putMarked(BlockPos pos, boolean culled) {
        // unbounded maps are a leak risk in multi-hour sessions; the scanner re-marks everything within 10 ticks.
        if (MARKED.size() > 200_000) {
            MARKED.clear();
        }
        return MARKED.put(pos.asLong(), (byte) (culled ? 1 : 0)) != 0;
    }

    public static void clearMarks() {
        MARKED.clear();
    }

    public static int markedCount() {
        return MARKED.size();
    }

    /** Flywheel 1.0.x is the verified API/impl generation. Unknown versions disable the feature instead of guessing. */
    public static void checkFlywheelVersion() {
        try {
            String version = ModList.get().getModContainerById("flywheel")
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("absent");
            CreateLod.LOG.info("[createlod] flywheel version = {}", version);
            boolean supported = version.startsWith("1.0.");
            if (!supported && LodConfig.get().strictFlywheelVersion) {
                disable("unsupported flywheel version " + version);
            }
        } catch (Throwable t) {
            disable(t);
        }
    }

    public static String blockId(BlockState state) {
        return idOf(state.getBlock());
    }

    private static String idOf(Block block) {
        String cached = ID_CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        var key = BuiltInRegistries.BLOCK.getKey(block);
        String id = key == null ? "" : key.toString();
        ID_CACHE.put(block, id);
        return id;
    }

    /**
     * @param wasCulled previous decision for this position (needed for distance hysteresis); pass false when
     *                  the visual is being created for the first time.
     */
    public static boolean shouldCull(BlockEntity be, Vec3 camPos, boolean wasCulled) {
        if (disabled || be == null || be.isRemoved()) {
            return false;
        }
        LodConfig cfg = LodConfig.get();
        if (!cfg.enabled) {
            return false;
        }
        Level level = be.getLevel();
        if (level == null || !level.isClientSide || camPos == null) {
            return false;
        }
        BlockPos pos = be.getBlockPos();
        BlockState state = be.getBlockState();
        String id = idOf(state.getBlock());
        ModelBounds.Box box = ModelBounds.get(id);

        // Safety valve: never cull while the camera is inside / hugging the model volume (spectator, freecam).
        AABB worldBox = new AABB(
                pos.getX() + box.minX, pos.getY() + box.minY, pos.getZ() + box.minZ,
                pos.getX() + box.maxX, pos.getY() + box.maxY, pos.getZ() + box.maxZ);
        if (worldBox.inflate(0.05).contains(camPos)) {
            return false;
        }

        // L1: block whose own casing model is a closed opaque box -> the rotating visual can never be seen.
        if (cfg.selfCasing && cfg.selfCasingBlocks.contains(id)) {
            return true;
        }

        // L2/L3: encased cogwheels (the middle band has 4 small transparent windows).
        // Guarded by unitScaled(): an encased LARGE cogwheel is only a 1x1x1 casing block while the rendered wheel
        // disc is ~1.875 blocks wide and pokes out of the block, so neither "windows covered" nor a distance cull
        // can ever be called zero-difference for it. It stays a visible control group.
        if (cfg.encasedCogwheelBlocks.contains(id) && box.unitScaled()) {
            if (cfg.windowCovered && cogwheelStructurallyCovered(level, pos, state)) {
                return true;
            }
            if (cfg.encasedFar && cfg.encasedLodDistance > 0.0D) {
                double dist = Math.sqrt(camPos.distanceToSqr(Vec3.atCenterOf(pos)));
                double threshold = wasCulled
                        ? cfg.encasedLodDistance * cfg.encasedLodHysteresis
                        : cfg.encasedLodDistance;
                if (dist > threshold) {
                    return true;
                }
            }
        }

        // L4: fully buried kinetic part (model AABB shell all opaque) -> zero visual difference.
        if (cfg.buried && cfg.buriedBlocks.contains(id) && Occluders.shellOccluded(level, pos, box)) {
            return true;
        }

        // L5: blanket namespace distance LOD (benchmark ceiling knob, off by default).
        if (cfg.farLod && cfg.farLodDistance > 0.0D) {
            int colon = id.indexOf(':');
            String namespace = colon < 0 ? id : id.substring(0, colon);
            if (cfg.farLodNamespaces.contains(namespace)) {
                double dist = Math.sqrt(camPos.distanceToSqr(Vec3.atCenterOf(pos)));
                double threshold = wasCulled ? cfg.farLodDistance * 0.85D : cfg.farLodDistance;
                if (dist > threshold) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Zero-difference structural test for a small (1x1x1) encased cogwheel:
     * <ul>
     *   <li>all four faces perpendicular to the rotation axis must be opaque (that covers the 4 window strips), and</li>
     *   <li>an end face with a shaft stub (top_shaft / bottom_shaft) must be opaque as well, otherwise the stub is visible.</li>
     * </ul>
     * The properties are read by name so this mod has no compile dependency on Create classes.
     */
    private static boolean cogwheelStructurallyCovered(LevelReader level, BlockPos pos, BlockState state) {
        Direction.Axis axis = axisOf(state);
        if (axis == null) {
            return false;
        }
        for (Direction dir : Direction.values()) {
            if (dir.getAxis() == axis) {
                continue;
            }
            if (!Occluders.opaque(level, pos.relative(dir))) {
                return false;
            }
        }
        boolean topShaft = boolOf(state, "top_shaft", false);
        boolean bottomShaft = boolOf(state, "bottom_shaft", false);
        if (topShaft) {
            Direction positive = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
            if (!Occluders.opaque(level, pos.relative(positive))) {
                return false;
            }
        }
        if (bottomShaft) {
            Direction negative = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
            if (!Occluders.opaque(level, pos.relative(negative))) {
                return false;
            }
        }
        return true;
    }

    private static Direction.Axis axisOf(BlockState state) {
        for (Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> entry
                : state.getValues().entrySet()) {
            if ("axis".equals(entry.getKey().getName()) && entry.getValue() instanceof Direction.Axis axis) {
                return axis;
            }
        }
        return null;
    }

    private static boolean boolOf(BlockState state, String propertyName, boolean fallback) {
        for (Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> entry
                : state.getValues().entrySet()) {
            if (propertyName.toLowerCase(Locale.ROOT).equals(entry.getKey().getName())
                    && entry.getValue() instanceof Boolean value) {
                return value;
            }
        }
        return fallback;
    }
}