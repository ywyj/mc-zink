package com.dsh.vkglshim;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public final class GL30C {
    public static int glGenVertexArrays() { return Shim.glGenVertexArrays(); }
    public static void glGenVertexArrays(IntBuffer a0) { Shim.glGenVertexArrays(a0); }
    public static void glBindVertexArray(int a0) { Shim.glBindVertexArray(a0); }
    public static void glDeleteVertexArrays(int a0) { Shim.glDeleteVertexArrays(a0); }
    public static int glGenFramebuffers() { return Shim.glGenFramebuffers(); }
    public static void glGenFramebuffers(IntBuffer a0) { Shim.glGenFramebuffers(a0); }
    public static void glBindFramebuffer(int a0, int a1) { Shim.glBindFramebuffer(a0, a1); }
    public static void glFramebufferTexture2D(int a0, int a1, int a2, int a3, int a4) { Shim.glFramebufferTexture2D(a0, a1, a2, a3, a4); }
    public static int glCheckFramebufferStatus(int a0) { return Shim.glCheckFramebufferStatus(a0); }
    public static void glDeleteFramebuffers(int a0) { Shim.glDeleteFramebuffers(a0); }
    public static int glGenRenderbuffers() { return Shim.glGenRenderbuffers(); }
    public static void glBindRenderbuffer(int a0, int a1) { Shim.glBindRenderbuffer(a0, a1); }
    public static void glRenderbufferStorage(int a0, int a1, int a2, int a3) { Shim.glRenderbufferStorage(a0, a1, a2, a3); }
    public static void glFramebufferRenderbuffer(int a0, int a1, int a2, int a3) { Shim.glFramebufferRenderbuffer(a0, a1, a2, a3); }
    public static void glDeleteRenderbuffers(int a0) { Shim.glDeleteRenderbuffers(a0); }
}
