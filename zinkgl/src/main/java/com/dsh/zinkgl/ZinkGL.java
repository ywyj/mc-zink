package com.dsh.zinkgl;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod("zinkgl")
public final class ZinkGL {
    public ZinkGL(IEventBus modBus, ModContainer container) {
        // Real work happens in Preload, triggered from the mixins before the window exists.
        // Calling again here is a cheap no-op fallback in case the mixin hook was not reached.
        Preload.ensureLoaded(null);
        StreamlineProvider.ensureProvided(null);
    }
}
