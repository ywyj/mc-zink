package com.dsh.dshcore.chain;

import com.dsh.dshcore.DshCoreMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server lifecycle + tick wiring for the chain engine (common side). */
@EventBusSubscriber(modid = DshCoreMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class ChainEvents {
    private ChainEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ChainEngine.loadConfig();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ChainEngine.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ChainEngine.tick();
    }
}
