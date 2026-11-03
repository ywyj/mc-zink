package com.dsh.zinkinfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostics + optional automation driven by &lt;gameDir&gt;/zinkinfo.cfg
 * Keys: join=&lt;worldName&gt;  shots=&lt;sec,sec,...&gt;  hidegui=true|false
 * Writes zinkinfo.txt (once) and zinkfps.txt (per second while in world).
 */
@EventBusSubscriber(modid = "zinkinfo", value = Dist.CLIENT)
public final class ZinkInfo {
    private static final Logger LOG = LoggerFactory.getLogger("ZinkInfo");
    private static final List<String> samples = new ArrayList<>();
    private static final Map<String, String> cfg = new LinkedHashMap<>();
    private static boolean reported = false;
    private static long start = -1L;
    private static long lastSample = 0L;
    private static boolean joinTried = false;
    private static int tickCount = 0;
    private static long levelSince = 0L;
    private static final List<Long> shotTimes = new ArrayList<>();
    private static final List<Long> shotsDone = new ArrayList<>();
    private static boolean quitDone = false;

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Path dir = mc.gameDirectory.toPath();
        if (!reported) {
            reported = true;
            start = System.currentTimeMillis();
            loadCfg(dir);
            List<String> lines = new ArrayList<>();
            try {
                lines.add("GL_VENDOR=" + GL11.glGetString(GL11.GL_VENDOR));
                lines.add("GL_RENDERER=" + GL11.glGetString(GL11.GL_RENDERER));
                lines.add("GL_VERSION=" + GL11.glGetString(GL11.GL_VERSION));
                lines.add("GLSL=" + GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
            } catch (Throwable t) {
                lines.add("GL_QUERY_FAILED=" + t);
            }
            lines.add("vulkanMod=" + classExists("net.vulkanmod.Initializer"));
            lines.add("iris=" + classExists("net.irisshaders.iris.Iris"));
            lines.add("cfg=" + cfg);
            String kf = KfDiag.test("tick");
            lines.add("kf=" + kf);
            LOG.info("[ZinkInfo] {}", kf);
            try { Files.write(dir.resolve("zinkinfo.txt"), lines); } catch (Exception ignored) {}
            LOG.info("[ZinkInfo] {}", String.join(" | ", lines));
        }
        tickCount++;

        // auto join world once title screen is idle
        String join = cfg.get("join");
        if (join != null && !join.isEmpty() && !joinTried && mc.level == null && tickCount > 80
                && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            joinTried = true;
            try {
                Object flows = mc.getClass().getMethod("createWorldOpenFlows").invoke(mc);
                Method target = null;
                for (Method m : flows.getClass().getMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (m.getName().equals("openWorld") && ps.length == 2 && ps[0] == String.class
                            && Runnable.class.isAssignableFrom(ps[1])) {
                        target = m; break;
                    }
                }
                if (target == null) {
                    for (Method m : flows.getClass().getMethods()) {
                        Class<?>[] ps = m.getParameterTypes();
                        if (ps.length == 2 && ps[0] == String.class && Runnable.class.isAssignableFrom(ps[1])) { target = m; break; }
                    }
                }
                if (target != null) {
                    target.invoke(flows, join, (Runnable) () -> LOG.warn("[ZinkInfo] openWorld reported failure"));
                    LOG.info("[ZinkInfo] auto-join requested world '{}' via {}", join, target.getName());
                } else {
                    LOG.warn("[ZinkInfo] auto-join: no (String,Runnable) method on {}", flows.getClass().getName());
                }
            } catch (Throwable t) {
                LOG.warn("[ZinkInfo] auto-join failed", t);
            }
        }

        if (mc.level == null || mc.player == null) {
            if (mc.level == null) levelSince = 0L;
            return;
        }
        if (levelSince == 0L) {
            levelSince = System.currentTimeMillis();
            LOG.info("[ZinkInfo] world loaded, timing started");
        }
        boolean hide = "true".equalsIgnoreCase(cfg.getOrDefault("hidegui", "false"));
        long now = System.currentTimeMillis();
        if (now - lastSample < 1000L) return;
        lastSample = now;
        if (hide) mc.options.hideGui = true;
        String iris = "none";
        try {
            Object r = Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPackName").invoke(null);
            if (r != null) iris = String.valueOf(r);
        } catch (Throwable ignored) {}
        int chunks = mc.levelRenderer.countRenderedSections();
        double rel = (now - levelSince) / 1000.0;
        samples.add(String.format("t=%.0fs fps=%d chunks=%d iris=%s fg=[%s]", rel, mc.getFps(), chunks, iris, fgDiag()));
        try { Files.write(dir.resolve("zinkfps.txt"), samples); } catch (Exception ignored) {}
        String quit = cfg.get("quit");
        if (!quitDone && quit != null && !quit.isEmpty()) {
            try {
                double q = Double.parseDouble(quit);
                if (q > 0 && rel >= q) {
                    quitDone = true;
                    LOG.warn("[ZinkInfo] reached quit={}s, exiting to flush DLSSG proxy summary", quit);
                    System.exit(0);
                }
            } catch (Throwable ignored) {}
        }
        for (int i = 0; i < shotTimes.size(); i++) {
            if (shotsDone.contains((long) i)) continue;
            if (rel >= shotTimes.get(i)) {
                shotsDone.add((long) i);
                try {
                    Screenshot.grab(dir.toFile(), "auto_" + (int) rel + "s.png", mc.getMainRenderTarget(), c -> {});
                    LOG.info("[ZinkInfo] screenshot auto_{}s.png", (int) rel);
                } catch (Throwable t) {
                    LOG.warn("[ZinkInfo] screenshot failed", t);
                }
            }
        }
    }

    private static void loadCfg(Path dir) {
        try {
            Path f = dir.resolve("zinkinfo.cfg");
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int i = s.indexOf('=');
                if (i <= 0) continue;
                String k = s.substring(0, i).trim().toLowerCase();
                String v = s.substring(i + 1).trim();
                cfg.put(k, v);
            }
            String shots = cfg.get("shots");
            if (shots != null) for (String s : shots.split(",")) {
                try { if (!s.trim().isEmpty()) shotTimes.add((long) Double.parseDouble(s.trim())); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static boolean classExists(String n) {
        try { Class.forName(n, false, ZinkInfo.class.getClassLoader()); return true; } catch (Throwable t) { return false; }
    }

    private static String fgDiag() {
        try {
            Class<?> c = Class.forName("io.homo.superresolution.common.framegeneration.FrameGeneration");
            Object id = c.getMethod("activeId").invoke(null);
            Object em = c.getMethod("activeExecutionModel").invoke(null);
            Object planned = c.getMethod("plannedGeneratedFrameCount").invoke(null);
            Object enabled = c.getMethod("isFrameGenerationEnabled").invoke(null);
            Object displayed = c.getMethod("displayedMode").invoke(null);
            Object appManaged = c.getMethod("activeApplicationManagedProviderId").invoke(null);
            return "active=" + id + ",exec=" + em + ",planned=" + planned + ",enabled=" + enabled
                    + ",mode=" + displayed + ",appManaged=" + appManaged;
        } catch (Throwable t) {
            return "err=" + t;
        }
    }
}
