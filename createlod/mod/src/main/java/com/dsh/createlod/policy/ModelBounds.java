package com.dsh.createlod.policy;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conservative local-space AABB of the rotating geometry of a Create kinetic block.
 *
 * <p>Values are derived from the block model JSON in create-1.21.1-6.0.10.jar (see evidence/model_bounds.csv
 * and work/model_bounds.py). They are deliberately a superset: a larger box only makes the shell test stricter
 * (fewer culls), never visually wrong.
 *
 * <p>Shell rule (this is what makes the cull provably invisible):
 * <ul>
 *   <li>A model whose AABB stays inside its own block cell is hidden when the 6 face neighbours are opaque full
 *       cubes: every surface point of the model is inside the cell, and the cell's 6 faces are covered.</li>
 *   <li>A model whose AABB crosses cell boundaries (large cogwheel, water wheel, steam engine, flywheel) is
 *       hidden when every cell <em>intersected by the AABB</em>, other than the model's own cell, is opaque. The
 *       shell is then the integer footprint of the AABB (union the 6 face neighbours of the origin) minus the
 *       origin. This is the conservative superset of the real requirement; it can only cause fewer culls.</li>
 * </ul>
 * All offsets are precomputed and cached, so a decision costs only {@code getBlockState} calls.
 */
public final class ModelBounds {
    private static final double EPS = 1e-4;
    private static final BlockPos[] NO_OFFSETS = new BlockPos[0];
    private static final Direction6 FACE_NEIGHBOURS = new Direction6();

    private static final class Direction6 {
        private final BlockPos[] offsets = {
                new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
                new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
                new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
        };
    }

    public static final class Box {
        public final double minX;
        public final double minY;
        public final double minZ;
        public final double maxX;
        public final double maxY;
        public final double maxZ;

        public Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        /** True if the box does not leave its own block cell, i.e. the cheap 6-neighbour shell is valid. */
        public boolean unitScaled() {
            return minX >= -EPS && minY >= -EPS && minZ >= -EPS
                    && maxX <= 1.0 + EPS && maxY <= 1.0 + EPS && maxZ <= 1.0 + EPS;
        }
    }

    /** Standard 1x1x1 block: shaft, cogwheel, and every fully encased shaft variant. */
    public static final Box UNIT = new Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
    /**
     * Large cogwheel: a flat wheel of ~1.875 block diameter, 4px thick, centred on the block.
     * Its disc leaves the block cell in both in-plane axes, hence a 26-neighbour shell.
     */
    public static final Box LARGE_COG = new Box(-0.5, -0.5, -0.5, 1.5, 1.5, 1.5);
    /** Water wheels: conservative 3x3x3 neighbourhood. */
    public static final Box WATER_WHEEL = new Box(-1.0, -1.0, -1.0, 2.0, 2.0, 2.0);
    /** Steam engine / flywheel: conservative 3x3x3 neighbourhood. */
    public static final Box BIG = new Box(-1.0, -1.0, -1.0, 2.0, 2.0, 2.0);

    private static final Map<String, Box> BY_ID = new HashMap<>();
    private static final Map<Box, BlockPos[]> SHELLS = new HashMap<>();

    static {
        String[] unit = {
                "create:shaft", "create:cogwheel",
                "create:andesite_encased_shaft", "create:brass_encased_shaft",
                "create:metal_girder_encased_shaft", "create:encased_chain_drive",
                "create:andesite_encased_cogwheel", "create:brass_encased_cogwheel",
                "create:gearbox", "create:brass_gearbox",
                "create:clutch", "create:gearshift", "create:creative_motor",
                "create:encased_fan", "create:gantry_shaft", "create:turntable",
                "create:mechanical_pump"
        };
        for (String id : unit) {
            BY_ID.put(id, UNIT);
        }
        BY_ID.put("create:large_cogwheel", LARGE_COG);
        BY_ID.put("create:andesite_encased_large_cogwheel", LARGE_COG);
        BY_ID.put("create:brass_encased_large_cogwheel", LARGE_COG);
        BY_ID.put("create:water_wheel", WATER_WHEEL);
        BY_ID.put("create:large_water_wheel", WATER_WHEEL);
        BY_ID.put("create:flywheel", BIG);
        BY_ID.put("create:steam_engine", BIG);
    }

    private ModelBounds() {
    }

    public static Box get(String blockId) {
        Box box = BY_ID.get(blockId);
        return box == null ? UNIT : box;
    }

    /** @return true only for the 1x1x1 family, i.e. where window/shell reasoning is exact. */
    public static boolean isUnitScaled(String blockId) {
        return get(blockId).unitScaled();
    }

    /** Cached shell offsets (relative positions that must all be opaque for the model to be invisible). */
    public static BlockPos[] shellOffsets(Box box) {
        BlockPos[] cached = SHELLS.get(box);
        if (cached != null) {
            return cached;
        }
        cached = computeShells(box);
        SHELLS.put(box, cached);
        return cached;
    }

    private static BlockPos[] computeShells(Box b) {
        int x0 = footprint(b.minX, false);
        int y0 = footprint(b.minY, false);
        int z0 = footprint(b.minZ, false);
        int x1 = footprint(b.maxX, true);
        int y1 = footprint(b.maxY, true);
        int z1 = footprint(b.maxZ, true);
        List<BlockPos> out = new ArrayList<>((x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) + 6);
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (x != 0 || y != 0 || z != 0) {
                        out.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        // The model's own cell still has to be covered on its 6 faces even if the AABB is fully inside it.
        for (BlockPos face : FACE_NEIGHBOURS.offsets) {
            boolean present = false;
            for (BlockPos p : out) {
                if (p.equals(face)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                out.add(face);
            }
        }
        BlockPos[] arr = out.toArray(NO_OFFSETS);
        return arr.length == 0 ? NO_OFFSETS : arr;
    }

    /**
     * First cell index intersected by this AABB boundary. A boundary that lies exactly on an integer is claimed by
     * the cell on its negative side (the face at {@code x=1.0} belongs to the block {@code [0,1)}), except on the
     * min side where the model surface at {@code x=0.0} is covered by the negative neighbour's face anyway; the
     * 6 face neighbours are added unconditionally, so the min side needs no special casing.
     */
    private static int footprint(double v, boolean maxSide) {
        return maxSide ? Mth.floor(v - EPS) : Mth.floor(v + EPS);
    }
}
