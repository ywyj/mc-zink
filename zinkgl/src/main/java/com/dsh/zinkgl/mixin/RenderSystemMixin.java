package com.dsh.zinkgl.mixin;

import com.dsh.zinkgl.SRMatrices;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {
    @Inject(method = "setProjectionMatrix", at = @At("HEAD"))
    private static void zinkgl$feedSRMatrices(Matrix4f projection, VertexSorting sorting, CallbackInfo ci) {
        SRMatrices.feed(projection);
    }
}
