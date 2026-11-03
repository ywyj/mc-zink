package com.dsh.dshcore.chain;

import com.dsh.dshcore.DshCoreMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.Arrays;

/** Optional frame-time HUD (fps / avg / 1% low / memory). Off by default; toggled by F10. */
@EventBusSubscriber(modid = DshCoreMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class FrameTimeHud {
    public static volatile boolean visible = false;
    private static final double[] SAMPLES = new double[240];
    private static int cursor = 0;
    private static int count = 0;
    private static long lastFrameNs = 0L;

    private FrameTimeHud() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible || mc.options.hideGui || mc.getWindow() == null) return;
        long now = System.nanoTime();
        if (lastFrameNs != 0L) {
            double ms = (now - lastFrameNs) / 1_000_000.0D;
            SAMPLES[cursor] = ms;
            cursor = (cursor + 1) % SAMPLES.length;
            if (count < SAMPLES.length) count++;
        }
        lastFrameNs = now;
        if (count == 0) return;
        double sum = 0.0D;
        for (int i = 0; i < count; i++) sum += SAMPLES[i];
        double avg = sum / count;
        double[] sorted = Arrays.copyOf(SAMPLES, count);
        Arrays.sort(sorted);
        int idx = Math.max(0, (int) Math.ceil(sorted.length * 0.99D) - 1);
        double low = sorted[idx];
        double fps = avg <= 0.0D ? 0.0D : 1000.0D / avg;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        GuiGraphics g = event.getGuiGraphics();
        int x = 4;
        int y = 4;
        int color = 0xFFFFFF;
        g.drawString(mc.font, String.format("FPS %.1f  avg %.2f ms  1%%low %.2f ms", fps, avg, low), x, y, color, true);
        g.drawString(mc.font, String.format("Heap %d MB  chain %s (%s)", usedMb,
                ChainEngine.config().settings.enabled ? "on" : "off", ChainEngine.config().settings.pattern), x, y + 10, color, true);
    }
}
