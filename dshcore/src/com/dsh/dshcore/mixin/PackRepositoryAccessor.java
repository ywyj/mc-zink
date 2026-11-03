package com.dsh.dshcore.mixin;

import java.util.Collection;
import java.util.List;

import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor for {@code PackRepository.rebuildSelected}, which is private in vanilla sources but made
 * public by NeoForge. Using a Mixin invoker keeps the call valid whichever visibility the runtime
 * class ends up with.
 */
@Mixin(PackRepository.class)
public interface PackRepositoryAccessor {
    @Invoker("rebuildSelected")
    List<Pack> dshcore$rebuildSelected(Collection<String> selectedIds);
}
