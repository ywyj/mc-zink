package com.dsh.zinkgl;

import com.sun.jna.NativeLibrary;
import com.sun.jna.WString;
import com.sun.jna.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies environment variables to the current process before the bundled Mesa opengl32.dll is
 * loaded, because Mesa reads its configuration from the CRT environment at library load time.
 * <p>
 * Java cannot set the process environment, so the values are pushed into the C runtime through
 * ucrtbase's {@code _putenv_s} (what Mesa's getenv reads), msvcrt's {@code _putenv} (older CRT) and
 * the Win32 environment block as a last resort. A {@code zinkgl.env} file next to the game directory
 * can override or extend the defaults, one {@code KEY=VALUE} per line, {@code #} starts a comment.
 */
public final class EnvLoader {
    private static final Logger LOG = LoggerFactory.getLogger("ZinkGL");
    private static volatile boolean applied = false;

    private EnvLoader() {}

    public static synchronized void apply(Path gameDir) {
        if (applied) return;
        applied = true;
        Map<String, String> values = new LinkedHashMap<>(defaults());
        readOverrides(gameDir, values);
        for (Map.Entry<String, String> e : values.entrySet()) {
            setEnv(e.getKey(), e.getValue());
        }
        LOG.info("[ZinkGL] applied {} Mesa environment variables: {}", values.size(), values.keySet());
    }

    private static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        // Vulkan WSI: let zink present immediately when the game asks for no vsync, instead of FIFO.
        m.put("MESA_VK_WSI_PRESENT_MODE", "fifo_relaxed");
        // Keep Mesa's shader cache on so the first-load stutter does not come back every launch.
        m.put("MESA_SHADER_CACHE_DISABLE", "false");
        m.put("MESA_GLSL_CACHE_DISABLE", "false");
        return m;
    }

    private static void readOverrides(Path gameDir, Map<String, String> values) {
        if (gameDir == null) return;
        Path file = gameDir.resolve("zinkgl.env");
        if (!Files.isRegularFile(file)) return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (value.isEmpty()) values.remove(key);
                else values.put(key, value);
            }
            LOG.info("[ZinkGL] read environment overrides from {}", file);
        } catch (Throwable t) {
            LOG.warn("[ZinkGL] failed to read {}", file, t);
        }
    }

    private static void setEnv(String key, String value) {
        try {
            Function fn = NativeLibrary.getInstance("ucrtbase").getFunction("_putenv_s");
            fn.invoke(Integer.class, new Object[]{key, value});
        } catch (Throwable ignored) {}
        try {
            Function fn = NativeLibrary.getInstance("msvcrt").getFunction("_putenv");
            fn.invoke(Integer.class, new Object[]{key + "=" + value});
        } catch (Throwable ignored) {}
        try {
            Function fn = NativeLibrary.getInstance("kernel32").getFunction("SetEnvironmentVariableW");
            fn.invoke(Integer.class, new Object[]{new WString(key), new WString(value)});
        } catch (Throwable ignored) {}
    }
}
