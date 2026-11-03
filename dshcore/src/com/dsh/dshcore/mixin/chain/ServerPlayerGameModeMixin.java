package com.dsh.dshcore.mixin.chain;

import com.dsh.dshcore.chain.ChainEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Triggers chain mining after a player really broke a block. Never cancels or changes vanilla
 * breaking behaviour: chain blocks are destroyed later by ChainEngine on server ticks.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow
    protected ServerPlayer player;

    @Shadow
    protected ServerLevel level;

    @Unique
    private BlockPos dshcore$originPos;

    @Unique
    private BlockState dshcore$originState;

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"))
    private void dshcore$captureOrigin(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        this.dshcore$originPos = pos == null ? null : pos.immutable();
        this.dshcore$originState = pos == null || this.level == null ? null : this.level.getBlockState(pos);
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", at = @At("RETURN"))
    private void dshcore$afterDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir.getReturnValueZ() && this.dshcore$originPos != null && this.dshcore$originState != null) {
                ChainEngine.onBlockBroken(this.player, this.level, this.dshcore$originPos, this.dshcore$originState);
            }
        } finally {
            this.dshcore$originPos = null;
            this.dshcore$originState = null;
        }
    }
}