package com.dsh.vkglshim;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public final class GL11C {
    public static int glGenTextures() { return Shim.glGenTextures(); }
    public static void glGenTextures(IntBuffer a0) { Shim.glGenTextures(a0); }
    public static void glBindTexture(int a0, int a1) { Shim.glBindTexture(a0, a1); }
    public static void glDeleteTextures(int a0) { Shim.glDeleteTextures(a0); }
    public static void glDeleteTextures(IntBuffer a0) { Shim.glDeleteTextures(a0); }
    public static void glTexParameteri(int a0, int a1, int a2) { Shim.glTexParameteri(a0, a1, a2); }
    public static void glTexParameterf(int a0, int a1, float a2) { Shim.glTexParameterf(a0, a1, a2); }
    public static void glTexImage2D(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, ByteBuffer a8) { Shim.glTexImage2D(a0, a1, a2, a3, a4, a5, a6, a7, a8); }
    public static void glTexImage2D(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, IntBuffer a8) { Shim.glTexImage2D(a0, a1, a2, a3, a4, a5, a6, a7, a8); }
    public static void glTexSubImage2D(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, ByteBuffer a8) { Shim.glTexSubImage2D(a0, a1, a2, a3, a4, a5, a6, a7, a8); }
    public static void glEnable(int a0) { Shim.glEnable(a0); }
    public static void glDisable(int a0) { Shim.glDisable(a0); }
    public static void glClear(int a0) { Shim.glClear(a0); }
    public static void glViewport(int a0, int a1, int a2, int a3) { Shim.glViewport(a0, a1, a2, a3); }
    public static void glDrawArrays(int a0, int a1, int a2) { Shim.glDrawArrays(a0, a1, a2); }
    public static void glDrawElements(int a0, int a1, int a2, long a3) { Shim.glDrawElements(a0, a1, a2, a3); }
    public static void glReadPixels(int a0, int a1, int a2, int a3, int a4, int a5, ByteBuffer a6) { Shim.glReadPixels(a0, a1, a2, a3, a4, a5, a6); }
    public static void glPixelStorei(int a0, int a1) { Shim.glPixelStorei(a0, a1); }
    public static void glBlendFunc(int a0, int a1) { Shim.glBlendFunc(a0, a1); }
    public static void glDepthFunc(int a0) { Shim.glDepthFunc(a0); }
    public static void glDepthMask(boolean a0) { Shim.glDepthMask(a0); }
    public static void glColorMask(boolean a0, boolean a1, boolean a2, boolean a3) { Shim.glColorMask(a0, a1, a2, a3); }
    public static void glScissor(int a0, int a1, int a2, int a3) { Shim.glScissor(a0, a1, a2, a3); }
    public static void glCullFace(int a0) { Shim.glCullFace(a0); }
    public static int glGetError() { return Shim.glGetError(); }
    public static int glGetInteger(int a0) { return Shim.glGetInteger(a0); }
    public static void glGetIntegerv(int a0, IntBuffer a1) { Shim.glGetIntegerv(a0, a1); }
    public static String glGetString(int a0) { return Shim.glGetString(a0); }
    public static void glActiveTexture(int a0) { Shim.glActiveTexture(a0); }
}
