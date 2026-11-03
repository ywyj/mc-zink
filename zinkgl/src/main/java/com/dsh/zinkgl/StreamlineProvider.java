package com.dsh.zinkgl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

/**
 * Provides the Streamline plugin directory to super_resolution's StreamlineDistribution so that the
 * DLSS Frame Generation backend ("wisteria:streamline") becomes available on zink/Vulkan.
 */
public final class StreamlineProvider {
    private static final Logger LOG = LoggerFactory.getLogger("ZinkGL");
    private static volatile boolean attempted = false;
    private static volatile boolean provided = false;
    private static volatile Path pluginDir = null;

    private StreamlineProvider() {}

    public static synchronized void ensureProvided(String[] args) {
        if (attempted) return;
        attempted = true;
        try {
            Path gameDir = args != null ? findGameDir(args) : null;
            if (gameDir == null) gameDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
            Path dir = gameDir.resolve("wisteria").toAbsolutePath();
            if (!Files.isRegularFile(dir.resolve("sl.interposer.dll"))) {
                LOG.warn("[ZinkGL] Streamline plugin dir missing sl.interposer.dll: {}", dir);
                return;
            }
            pluginDir = dir;
            Class<?> dist = Class.forName("io.homo.superresolution.api.StreamlineDistribution");
            java.lang.reflect.Method provide = dist.getMethod("provide", Supplier.class);
            Supplier<Path> supplier = () -> dir;
            provide.invoke(null, supplier);
            provided = true;
            LOG.info("[ZinkGL] provided Streamline plugin directory {} to StreamlineDistribution", dir);
        } catch (Throwable t) {
            LOG.error("[ZinkGL] failed to provide Streamline plugin directory: {}", t.toString());
        }
    }

    private static Path findGameDir(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--gameDir".equals(args[i])) {
                try { return Paths.get(args[i + 1]).toAbsolutePath(); } catch (Exception ignored) { return null; }
            }
        }
        return null;
    }

    public static boolean isProvided() { return provided; }
    public static Path pluginDir() { return pluginDir; }
}
