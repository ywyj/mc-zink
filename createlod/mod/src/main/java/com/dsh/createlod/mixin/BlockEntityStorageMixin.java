package com.dsh.createlod.mixin;

import com.dsh.createlod.policy.PolicyEngine;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The single Flywheel hook: suppress visual creation for block entities the LOD policy considers invisible
 * (or below the distance LOD). Returning null is explicitly supported by Flywheel's storage:
 *
 * <pre>
 * // dev.engine_room.flywheel.impl.visualization.storage.Storage#add (1.0.6)
 * Visual visual = this.visuals.get(obj);
 * if (visual == null) {
 *     visual = this.createRaw(context, obj, partialTick);
 *     if (visual != null) { this.setup(visual, partialTick); this.visuals.put(obj, visual); }
 * }
 * </pre>
 *
 * so a null return means "no visual at all": nothing is drawn and nothing is registered. It does NOT fall back
 * to the vanilla renderer: Create's block entity renderers bail out whenever Flywheel visualization is supported
 * (KineticBlockEntityRenderer.renderSafe, create-1.21.1-6.0.10, line 39).
 *
 * <p>Compatibility: @Pseudo + require = 0 + mixin config "required": false means a missing or renamed Flywheel
 * class/方法 never crashes the game; the injection is simply not applied and the scanner fallback still removes
 * visuals after the fact.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage", remap = false)
public abstract class BlockEntityStorageMixin {

    @Inject(
            method = "createRaw(Ldev/engine_room/flywheel/api/visualization/VisualizationContext;Lnet/minecraft/world/level/block/entity/BlockEntity;F)Ldev/engine_room/flywheel/api/visual/BlockEntityVisual;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void createlod$skipHiddenVisual(VisualizationContext context, BlockEntity blockEntity, float partialTick,
                                             CallbackInfoReturnable<BlockEntityVisual<?>> cir) {
        try {
            if (PolicyEngine.isDisabled()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            Vec3 camera = minecraft.gameRenderer == null
                    ? null
                    : minecraft.gameRenderer.getMainCamera().getPosition();
            boolean wasCulled = PolicyEngine.isMarkedCulled(blockEntity.getBlockPos());
            if (PolicyEngine.shouldCull(blockEntity, camera, wasCulled)) {
                cir.setReturnValue(null);
            }
        } catch (Throwable t) {
            PolicyEngine.disable(t);
        }
    }
}
