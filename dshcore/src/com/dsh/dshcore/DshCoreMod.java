package com.dsh.dshcore;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.Properties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DSH Core self-check entry point.
 *
 * <p>Prints exactly one machine-greppable line at client setup so a first-boot verification
 * (and later A/B runs) can confirm what actually got loaded without opening any GUI:
 * version, declared mod/mixin counts, injection points, access-transformers, JVM uptime and heap.
 *
 * <p>Pure reporting: it never alters game state and never throws.
 */
@Mod(DshCoreMod.MOD_ID)
public final class DshCoreMod {
    public static final String MOD_ID = "dshcore";
    private static final String PROPS = "/dshcore-selfcheck.properties";
    private static final Logger LOGGER = LoggerFactory.getLogger("DSH Core");

    public DshCoreMod(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        String line;
        try {
            Properties p = new Properties();
            try (InputStream in = DshCoreMod.class.getResourceAsStream(PROPS)) {
                if (in != null) {
                    p.load(in);
                }
            }
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
            long maxMb = rt.maxMemory() / (1024L * 1024L);
            long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
            line = String.format(
                    "[DSH Core] version=%s mods=%s mixinConfigs=%s injectionPoints=%s"
                            + " accessTransformers=%s jvmUptimeMs=%d heapUsedMB=%d heapMaxMB=%d"
                            + " entityculling=%s",
                    p.getProperty("version", "unknown"),
                    p.getProperty("mods", "?"),
                    p.getProperty("mixinConfigs", "?"),
                    p.getProperty("injectionPoints", "?"),
                    p.getProperty("accessTransformers", "?"),
                    uptimeMs, usedMb, maxMb,
                    p.getProperty("entityculling", "?"));
        } catch (Throwable t) {
            line = "[DSH Core] version=unknown selfcheck=FAILED " + t.getClass().getSimpleName();
        }
        System.out.println(line);
        LOGGER.info(line);
    }
}
