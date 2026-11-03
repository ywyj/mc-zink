package com.dsh.zinkgl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Loads the bundled Mesa opengl32.dll into the process before GLFW initialises WGL. */
public final class Preload {
    private static final Logger LOG = LoggerFactory.getLogger("ZinkGL");
    private static volatile boolean attempted = false;
    private static volatile boolean loaded = false;

    private Preload() {}

    public static synchronized void ensureLoaded(String[] args) {
        if (attempted) return;
        attempted = true;
        try {
            Path gameDir = args != null ? findGameDir(args) : null;
            if (gameDir == null) gameDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
            EnvLoader.apply(gameDir);
            loadMesa(gameDir);
            loadDlssgProxy(gameDir);
            StreamlineProvider.ensureProvided(args);
        } catch (Throwable t) {
            LOG.error("[ZinkGL] preload failure: {}", t.toString(), t);
        }
    }

    private static void loadMesa(Path gameDir) {
        Path dll = gameDir.resolve("mesa").resolve("opengl32.dll");
        if (!Files.isRegularFile(dll)) {
            Path alt = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().resolve("mesa").resolve("opengl32.dll");
            if (Files.isRegularFile(alt)) dll = alt;
        }
        if (!Files.isRegularFile(dll)) {
            LOG.error("[ZinkGL] Mesa opengl32.dll NOT found (gameDir={}) - falling back to system OpenGL", gameDir);
            return;
        }
        System.load(dll.toAbsolutePath().toString());
        loaded = true;
        LOG.info("[ZinkGL] preloaded Mesa opengl32.dll from {} ({} bytes) - GL will be translated to Vulkan via zink",
                dll.toAbsolutePath(), size(dll));
    }

    /** Loads the DLSS-G SM86 replacement proxy (version.dll) so it can redirect nvngx_dlssg.dll loads. */
    private static void loadDlssgProxy(Path gameDir) {
        Path dll = gameDir.resolve("dlssg").resolve("version.dll");
        if (!Files.isRegularFile(dll)) {
            LOG.warn("[ZinkGL] DLSSG proxy not found at {}", dll);
            return;
        }
        try {
            System.load(dll.toAbsolutePath().toString());
            LOG.info("[ZinkGL] loaded DLSSG SM86 proxy from {}", dll.toAbsolutePath());
        } catch (Throwable t) {
            LOG.error("[ZinkGL] failed to load DLSSG proxy: {}", t.toString());
        }
    }

    private static long size(Path p) {
        try { return Files.size(p); } catch (Exception e) { return -1; }
    }

    private static Path findGameDir(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--gameDir".equals(args[i])) {
                try { return Paths.get(args[i + 1]).toAbsolutePath(); } catch (Exception ignored) { return null; }
            }
        }
        return null;
    }

    public static boolean isLoaded() { return loaded; }
}
