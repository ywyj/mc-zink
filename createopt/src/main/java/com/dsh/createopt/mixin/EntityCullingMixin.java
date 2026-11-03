package com.dsh.createopt.mixin;

import com.dsh.createopt.CreateOpt;
import com.dsh.createopt.Culling;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体剔除：距离（默认 64 格）+ 可选遮挡射线。
 * 1.1.0 修复：
 * - keepBosses 原来用 LivingEntity.isCurrentlyGlowing()（发光≠boss），改为真实 boss 判定；
 * - keepNamed 原来只看 Mob，改为 Entity.hasCustomName()；
 * - 距离改用摄像机到实体世界坐标，而不是渲染用的相机相对偏移；
 * - Sable 子关卡的实体会被 LevelRenderer 以变换后的坐标送进来（entity.level() != mc.level），直接跳过，避免误剔。
 * 只读配置 + 世界方块状态，不碰 GL 调用/渲染管线，Sodium/Iris/zink 下行为与原生 GL 一致。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityCullingMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void createopt$cull(Entity entity, double x, double y, double z, float rotationYaw, float partialTick,
                                PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
        CreateOpt.Settings s = CreateOpt.SETTINGS;
        boolean distanceOn = s.entityCullDistance > 0.0D;
        if (!distanceOn && !s.occlusion) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) return;
        Entity cameraEntity = mc.getCameraEntity();
        if (cameraEntity == null || entity == cameraEntity) return;
        // Sable 子关卡 / 其它 ClientLevel 的实体：坐标已被上层变换，跳过
        if (entity.level() != mc.level) return;

        if (s.keepPlayers && entity instanceof Player) return;
        if (s.keepBosses && Culling.isBoss(entity)) return;
        if (s.keepNamed && entity.hasCustomName()) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 target = new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ());
        double dist = cam.distanceTo(target);

        if (distanceOn && dist > s.entityCullDistance) {
            CreateOpt.debugLog("[CreateOpt] distance-cull entity {} at {} blocks", entity.getType(), String.format("%.1f", dist));
            ci.cancel();
            return;
        }
        if (s.occlusion && Culling.isEntityOccluded(entity.getId(), cam, target)) {
            ci.cancel();
        }
    }
}
