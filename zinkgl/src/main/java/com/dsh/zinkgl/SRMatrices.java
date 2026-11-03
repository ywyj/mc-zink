package com.dsh.zinkgl;

import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Feeds Minecraft's projection/modelview matrices into super_resolution's AlgorithmManager.
 * SR only does this from its shader-compat Iris mixin, so without a SR-compatible shader pack
 * DLSS-G constants fail with "cameraViewToClip is not invertible".
 */
public final class SRMatrices {
    private static final Logger LOG = LoggerFactory.getLogger("ZinkGL");
    private static volatile MethodHandle setter;
    private static volatile boolean failed = false;
    private static volatile boolean logged = false;

    private SRMatrices() {}

    private static MethodHandle setter() {
        MethodHandle h = setter;
        if (h != null || failed) return h;
        try {
            Class<?> c = Class.forName("io.homo.superresolution.common.upscale.AlgorithmManager");
            h = MethodHandles.lookup().findStatic(c, "setMatrixVanilla",
                    MethodType.methodType(void.class, Matrix4f.class, Matrix4f.class));
            setter = h;
            return h;
        } catch (Throwable t) {
            failed = true;
            LOG.warn("[ZinkGL] super_resolution AlgorithmManager not usable for matrix feed: {}", t.toString());
            return null;
        }
    }

    public static void feed(Matrix4f projection) {
        try {
            MethodHandle h = setter();
            if (h == null || projection == null) return;
            Matrix4f modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix();
            if (modelView == null) return;
            h.invokeExact(projection, modelView);
            if (!logged) { logged = true; LOG.info("[ZinkGL] feeding Minecraft projection/modelview into super_resolution FG constants"); }
        } catch (Throwable ignored) {
        }
    }
}
