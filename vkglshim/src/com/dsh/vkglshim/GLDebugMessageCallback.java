package com.dsh.vkglshim;

/** 对应 org.lwjgl.opengl.GLDebugMessageCallback 的最小替身（VulkanMod 下没有 GL debug 回调）。 */
public class GLDebugMessageCallback implements GLDebugMessageCallbackI {
    public GLDebugMessageCallback() { }
    public static GLDebugMessageCallback create() { return new GLDebugMessageCallback(); }
    public static GLDebugMessageCallback create(long address) { return new GLDebugMessageCallback(); }
    public static GLDebugMessageCallback create(GLDebugMessageCallbackI cb) { return new GLDebugMessageCallback(); }
    public static GLDebugMessageCallback create(java.util.function.Consumer<String> cb) { return new GLDebugMessageCallback(); }
    @Override public void invoke(int source, int type, int id, int severity, int length, long message, long userParam) { }
    public void free() { }
    public long address() { return 0L; }
}
