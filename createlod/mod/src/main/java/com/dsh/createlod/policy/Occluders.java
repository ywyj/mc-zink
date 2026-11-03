package com.dsh.createlod.policy;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The single visibility predicate used by every LOD rule.
 *
 * <p>An "opaque" block is a full, model-rendered cube: it both cancels neighbour faces ({@code canOcclude}) and
 * fills its whole collision/occlusion volume. Translucent or partial blocks (glass, leaves, slabs, fences,
 * fluids) intentionally fail the test: failing means "do not cull", i.e. the safe direction.
 */
public final class Occluders {
    private Occluders() {
    }

    public static boolean opaque(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        if (!state.canOcclude()) {
            return false;
        }
        // isSolidRender == canOcclude && occlusion shape is a full cube (vanilla semantics)
        return state.isSolidRender(level, pos);
    }

    /**
     * Makes the model AABB invisible from any camera outside the shell: the shell itself must be unbroken opaque.
     */
    public static boolean shellOccluded(BlockGetter level, BlockPos origin, ModelBounds.Box box) {
        for (BlockPos off : ModelBounds.shellOffsets(box)) {
            if (!opaque(level, origin.offset(off))) {
                return false;
            }
        }
        return true;
    }
}
