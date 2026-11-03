package com.dsh.createopt.mixin;

import com.dsh.createopt.CreateOpt;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 可选：关闭原版实体脚下 blob 阴影（光影通常自带 entity shadow，可省一批 draw）。
 * 默认关；Iris 光影下 Iris 自己会在 renderShadow HEAD cancel 并画自己的阴影，本 mixin 与其同注入点但可组合，无冲突。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityShadowMixin {

    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
    private static void createopt$disableVanillaShadow(PoseStack poseStack, MultiBufferSource bufferSource,
                                                       Entity entity, float opacity, float partialTick,
                                                       LevelReader level, float radius, CallbackInfo ci) {
        if (CreateOpt.SETTINGS.disableVanillaEntityShadow) ci.cancel();
    }
}
