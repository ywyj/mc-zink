package com.dsh.zinkgl.mixin;

import com.dsh.zinkgl.Preload;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Main.class)
public class MainMixin {
    @Inject(method = "main", at = @At("HEAD"))
    private static void zinkgl$preload(String[] args, CallbackInfo ci) {
        Preload.ensureLoaded(args);
    }
}
