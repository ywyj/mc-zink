package com.dsh.vkglshim;

/** 对应 org.lwjgl.opengl.GLDebugMessageCallbackI 的最小替身（VulkanMod 下没有 GL debug 回调）。 */
public interface GLDebugMessageCallbackI {
    void invoke(int source, int type, int id, int severity, int length, long message, long userParam);
}
