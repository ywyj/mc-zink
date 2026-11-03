package com.dsh.dshcore.mixin;

import java.util.List;

import net.minecraft.client.Options;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DSH Core: clean-room reimplementation of the 0Pack2Reload behaviour.
 *
 * Vanilla {@code Options.updateResourcePacks(PackRepository)} snapshots the option lists before and
 * after rebuilding them from the repository, and schedules a full resource reload if they differ.
 * On a fresh profile {@code options.txt} has no resourcePacks list yet, so the "before" snapshot is
 * empty while the repository already holds its default selection: the comparison differs and an
 * extra full reload is scheduled although the user never selected anything.
 *
 * This injector pre-fills the still-empty option lists from the repository's default selection so
 * that vanilla's comparison becomes idempotent. The vanilla method is never cancelled, so all of
 * vanilla's behaviour (save, incompatible tracking, reload decision) stays authoritative.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Shadow
    public List<String> resourcePacks;

    @Shadow
    public List<String> incompatibleResourcePacks;

    @Inject(method = "updateResourcePacks", at = @At("HEAD"))
    private void dshcore$prefillEmptyResourcePacks(PackRepository repository, CallbackInfo ci) {
        if (!this.resourcePacks.isEmpty()) {
            return;
        }
        PackRepositoryAccessor accessor = (PackRepositoryAccessor) (Object) repository;
        for (Pack pack : accessor.dshcore$rebuildSelected(List.of())) {
            if (pack.isFixedPosition() || pack.isHidden()) {
                continue;
            }
            this.resourcePacks.add(pack.getId());
            if (!pack.getCompatibility().isCompatible()) {
                this.incompatibleResourcePacks.add(pack.getId());
            }
        }
    }
}
