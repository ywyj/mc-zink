package com.dsh.vkglshim;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public final class GL42C {
    private GL42C() {}
    public static int glGenTextures() { return Shim.glGenTextures(); }
    public static void glGenTextures(IntBuffer a0) { Shim.glGenTextures(a0); }
    public static void glBindTexture(int a0, int a1) { Shim.glBindTexture(a0, a1); }
    public static void glDeleteTextures(int a0) { Shim.glDeleteTextures(a0); }
    public static void glDeleteTextures(IntBuffer a0) { Shim.glDeleteTextures(a0); }
    public static void glTexParameteri(int a0, int a1, int a2) { Shim.glTexParameteri(a0, a1, a2); }
    public static void glTexParameterf(int a0, int a1, float a2) { Shim.glTexParameterf(a0, a1, a2); }
    public static void glTexImage2D(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, ByteBuffer a8) { Shim.glTexImage2D(a0, a1, a2, a3, a4, a5, a6, a7, a8); }
    public static void glTexSubImage2D(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, ByteBuffer a8) { Shim.glTexSubImage2D(a0, a1, a2, a3, a4, a5, a6, a7, a8); }
    public static void glEnable(int a0) { Shim.glEnable(a0); }
    public static void glDisable(int a0) { Shim.glDisable(a0); }
    public static void glClear(int a0) { Shim.glClear(a0); }
    public static void glViewport(int a0, int a1, int a2, int a3) { Shim.glViewport(a0, a1, a2, a3); }
    public static void glBlendFunc(int a0, int a1) { Shim.glBlendFunc(a0, a1); }
    public static void glPixelStorei(int a0, int a1) { Shim.glPixelStorei(a0, a1); }
    public static void glDepthFunc(int a0) { Shim.glDepthFunc(a0); }
    public static void glDepthMask(boolean a0) { Shim.glDepthMask(a0); }
    public static int glGenBuffers() { return Shim.glGenBuffers(); }
    public static void glGenBuffers(IntBuffer a0) { Shim.glGenBuffers(a0); }
    public static void glBindBuffer(int a0, int a1) { Shim.glBindBuffer(a0, a1); }
    public static void glBufferData(int a0, ByteBuffer a1, int a2) { Shim.glBufferData(a0, a1, a2); }
    public static void glDeleteBuffers(int a0) { Shim.glDeleteBuffers(a0); }
    public static int glCreateShader(int a0) { return Shim.glCreateShader(a0); }
    public static int glCreateProgram() { return Shim.glCreateProgram(); }
    public static void glAttachShader(int a0, int a1) { Shim.glAttachShader(a0, a1); }
    public static void glLinkProgram(int a0) { Shim.glLinkProgram(a0); }
    public static void glUseProgram(int a0) { Shim.glUseProgram(a0); }
    public static void glDeleteShader(int a0) { Shim.glDeleteShader(a0); }
    public static void glDeleteProgram(int a0) { Shim.glDeleteProgram(a0); }
    public static int glGetShaderi(int a0, int a1) { return Shim.glGetShaderi(a0, a1); }
    public static int glGetProgrami(int a0, int a1) { return Shim.glGetProgrami(a0, a1); }
    public static int glGenVertexArrays() { return Shim.glGenVertexArrays(); }
    public static void glBindVertexArray(int a0) { Shim.glBindVertexArray(a0); }
    public static void glDeleteVertexArrays(int a0) { Shim.glDeleteVertexArrays(a0); }
    public static int glGenFramebuffers() { return Shim.glGenFramebuffers(); }
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
