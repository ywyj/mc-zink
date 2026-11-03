package com.dsh.createopt.mixin;

import com.dsh.createopt.CreateOpt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 粒子距离剔除：远处粒子根本不进队列 —— 省 tick、省提交、省显存。
 * 纯逻辑，不碰 GL，Sodium/Iris/zink 与原生 GL 都能用。
 * 注入点在 ParticleEngine.add 的 HEAD：AsyncParticles 的队列注入在方法内部，被我们取消后它也不会入队（顺序安全）。
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleCullingMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void createopt$cullParticle(Particle particle, CallbackInfo ci) {
        CreateOpt.Settings s = CreateOpt.SETTINGS;
        if (s.particleCullDistance <= 0.0D) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 pos = particle.getPos();
        double limit = s.particleCullDistance;
        double distSq = cam.distanceToSqr(pos);
        if (distSq > limit * limit) {
            CreateOpt.debugLog("[CreateOpt] cull particle at {} blocks", String.format("%.1f", Math.sqrt(distSq)));
            ci.cancel();
        }
    }
}
