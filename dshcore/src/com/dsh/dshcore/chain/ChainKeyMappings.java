package com.dsh.dshcore.chain;

import com.dsh.dshcore.DshCoreMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** Client key bindings (registration only; polling lives in ChainClientTick). */
@EventBusSubscriber(modid = DshCoreMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ChainKeyMappings {
    public static KeyMapping toggleChain;
    public static KeyMapping toggleHud;

    private ChainKeyMappings() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        toggleChain = new KeyMapping("key.dshcore.chain_toggle", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), "key.categories.misc");
        toggleHud = new KeyMapping("key.dshcore.hud_toggle", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F10, "key.categories.misc");
        event.register(toggleChain);
        event.register(toggleHud);
    }
}
