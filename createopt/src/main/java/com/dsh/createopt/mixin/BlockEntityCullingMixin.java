package com.dsh.createopt.mixin;

import com.dsh.createopt.CreateOpt;
import com.dsh.createopt.Culling;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 方块实体剔除：距离（默认 48 格）+ 可选遮挡射线（跳过目标方块本身）。
 * Create 的机械 BER（轴承/机械手/工作站等）大部分走这里；
 * 但 Flywheel 渲染的 contraption 不走 BlockEntityRenderDispatcher，本 mixin 覆盖不到（见 REPORT）。
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullingMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void createopt$cullBE(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
                                  MultiBufferSource bufferSource, CallbackInfo ci) {
        CreateOpt.Settings s = CreateOpt.SETTINGS;
        boolean distanceOn = s.blockEntityCullDistance > 0.0D;
        if (!distanceOn && !s.occlusion) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) return;
        // Sable 子关卡 / 其它 ClientLevel 的方块实体：跳过
        if (blockEntity.getLevel() != mc.level) return;

        BlockPos pos = blockEntity.getBlockPos();
        Vec3 target = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double dist = cam.distanceTo(target);

        if (distanceOn && dist > s.blockEntityCullDistance) {
            CreateOpt.debugLog("[CreateOpt] distance-cull block entity {} at {} blocks", blockEntity.getType(), String.format("%.1f", dist));
            ci.cancel();
            return;
        }
        if (s.occlusion && Culling.isBlockEntityOccluded(pos, cam, target)) {
            ci.cancel();
        }
    }
}
