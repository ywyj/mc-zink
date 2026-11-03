package com.dsh.dshcore.chain;

import com.dsh.dshcore.DshCoreMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Polls key bindings on the client; neither path touches rendering/GL state. */
@EventBusSubscriber(modid = DshCoreMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ChainClientTick {
    private ChainClientTick() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (ChainKeyMappings.toggleChain.consumeClick()) {
            ChainSettings s = ChainEngine.config().settings;
            s.enabled = !s.enabled;
            ChainEngine.config().save();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[dshcore] chain " + (s.enabled ? "enabled" : "disabled")), true);
            }
        }
        while (ChainKeyMappings.toggleHud.consumeClick()) {
            FrameTimeHud.visible = !FrameTimeHud.visible;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[dshcore] frame HUD " + (FrameTimeHud.visible ? "on" : "off")), true);
            }
        }
    }
}
