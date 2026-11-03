package com.dsh.createopt;

import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;

import java.util.Locale;

/**
 * 渲染环境探测（1.1.0 新增，替代旧的 net.vulkanmod.Initializer 判定）。
 *
 * 只读 GL_RENDERER / GL_VENDOR / GL_VERSION，一次性打日志，不参与任何行为分支：
 * zink（Mesa 把 GL 4.6 翻译到 Vulkan）在 Java 侧就是一个标准 GL 驱动，走标准 GL 路径。
 * 这样无论用户跑原生 GL、zink 还是别的 GL→Vulkan 层，模组行为一致，不会因为"检测不到 VulkanMod"而失效。
 */
public final class Env {
    private Env() {}

    private static boolean logged = false;
    private static boolean zink = false;
    private static String renderer = "unknown";
    private static String vendor = "unknown";
    private static String version = "unknown";

    /** 注册在 RenderFrameEvent.Pre 上：等 GL 上下文就绪后只执行一次 */
    public static void onFrame(RenderFrameEvent.Pre event) {
        logOnce();
    }

    public static synchronized void logOnce() {
        if (logged) return;
        logged = true;
        try {
            renderer = safe(GL11.glGetString(GL11.GL_RENDERER));
            vendor = safe(GL11.glGetString(GL11.GL_VENDOR));
            version = safe(GL11.glGetString(GL11.GL_VERSION));
        } catch (Throwable t) {
            renderer = "unavailable(" + t.getClass().getSimpleName() + ")";
        }
        String r = renderer == null ? "" : renderer.toLowerCase(Locale.ROOT);
        zink = r.contains("zink");
        CreateOpt.LOG.info("[CreateOpt] GL env: renderer=\"{}\" vendor=\"{}\" version=\"{}\" zink={}", renderer, vendor, version, zink);
        CreateOpt.LOG.info("[CreateOpt] render path: standard GL, no VulkanMod gating (env detection is diagnostic-only)");
    }

    private static String safe(String s) {
        return s == null ? "null" : s;
    }

    public static boolean isZink() {
        return zink;
    }
}
