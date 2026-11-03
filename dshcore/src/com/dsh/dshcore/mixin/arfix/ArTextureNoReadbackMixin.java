package com.dsh.dshcore.mixin.arfix;

import com.dsh.dshcore.arfix.ArFixGate;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AR (Accelerated Rendering, merged into dshcore) 纹理读回修复：
 * TextureUtils.downloadTexture 在门控开启时直接返回 null，不发起 glGetTexImage。
 * 调用方 CulledMeshCollector -> IAcceleratedVertexConsumer.downloadTexture；
 * CullerUtils.shouldCull(Vertex[], NativeImage) 首行 image == null -> return false，
 * 因此 null 等价于“不裁剪、保留所有 quad”，不会 NPE（纯 CPU 侧少做裁剪，最多少量 GPU）。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.utils.TextureUtils", remap = false)
public class ArTextureNoReadbackMixin {

    @Inject(
            method = "downloadTexture(Lnet/minecraft/client/renderer/RenderType;I)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void dsh$skipTextureReadback(RenderType renderType, int mip,
            CallbackInfoReturnable<NativeImage> cir) {
        if (ArFixGate.noReadback()) {
            cir.setReturnValue(null);
        }
    }
}
