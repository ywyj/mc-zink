package com.dsh.vkglshim;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/** GL -> VulkanMod 过渡层：给直接调用 LWJGL GL 的模组（Veil/Sable/Xaero/ldlib2）提供状态化替身。
 *  GPU 工作仍由 VulkanMod 负责；这里保证对象/状态语义成立，渲染缺失由后续逐步补齐。 */
public final class Shim {
    private Shim() {}
    static final Map<Integer,Integer> TEX = new HashMap<>();
    static final Map<Integer,Integer> BUF = new HashMap<>();
    static final Map<Integer,Integer> SHADER = new HashMap<>();
    static final Map<Integer,Integer> PROGRAM = new HashMap<>();
    static final Map<Integer,Integer> VAO = new HashMap<>();
    static final Map<Integer,Integer> FBO = new HashMap<>();
    static final Map<Integer,Integer> RBO = new HashMap<>();
    static int next = 1;
    static int boundTexture, boundBuffer, boundProgram, boundVao, boundFbo, boundRbo, activeTexture;

    static synchronized int id() { return next++; }

    static int glGenTextures() { int i = id(); TEX.put(i, 1); return i; }
    static void glGenTextures(IntBuffer out) { while (out.remaining() > 0) out.put(glGenTextures()); }
    static void glBindTexture(int a, int b) { boundTexture = b; }
    static void glDeleteTextures(int a) { TEX.remove(a); }
    static void glDeleteTextures(IntBuffer a) { while (a.remaining() > 0) TEX.remove(a.get()); }
    static void glTexParameteri(int a, int b, int c) {}
    static void glTexParameterf(int a, int b, float c) {}
    static void glTexImage2D(int a, int b, int c, int d, int e, int f, int g, int h, ByteBuffer i) {}
    static void glTexImage2D(int a, int b, int c, int d, int e, int f, int g, int h, IntBuffer i) {}
    static void glTexSubImage2D(int a, int b, int c, int d, int e, int f, int g, int h, ByteBuffer i) {}
    static void glEnable(int a) {}
    static void glDisable(int a) {}
    static void glClear(int a) {}
    static void glViewport(int a, int b, int c, int d) {}
    static void glDrawArrays(int a, int b, int c) {}
    static void glDrawElements(int a, int b, int c, long d) {}
    static void glReadPixels(int a, int b, int c, int d, int e, int f, ByteBuffer g) {}
    static void glPixelStorei(int a, int b) {}
    static void glBlendFunc(int a, int b) {}
    static void glBlendFuncSeparate(int a, int b, int c, int d) {}
    static void glDepthFunc(int a) {}
    static void glDepthMask(boolean a) {}
    static void glColorMask(boolean a, boolean b, boolean c, boolean d) {}
    static void glScissor(int a, int b, int c, int d) {}
    static void glCullFace(int a) {}
    static int glGetError() { return 0; }
    static int glGetInteger(int a) { return 0; }
    static void glGetIntegerv(int a, IntBuffer b) { if (b.remaining() > 0) b.put(0); }
    static String glGetString(int a) { return "vkglshim"; }
    static void glActiveTexture(int a) { activeTexture = a; }

    static int glGenBuffers() { int i = id(); BUF.put(i, 1); return i; }
    static void glGenBuffers(IntBuffer out) { while (out.remaining() > 0) out.put(glGenBuffers()); }
    static void glBindBuffer(int a, int b) { boundBuffer = b; }
    static void glBufferData(int a, ByteBuffer b, int c) {}
    static void glBufferSubData(int a, long b, ByteBuffer c) {}
    static void glDeleteBuffers(int a) { BUF.remove(a); }
    static void glDeleteBuffers(IntBuffer a) { while (a.remaining() > 0) BUF.remove(a.get()); }

    static int glCreateShader(int a) { int i = id(); SHADER.put(i, a); return i; }
    static void glShaderSource(int a, CharSequence b) {}
    static void glCompileShader(int a) {}
    static int glCreateProgram() { int i = id(); PROGRAM.put(i, 1); return i; }
    static void glAttachShader(int a, int b) {}
    static void glLinkProgram(int a) {}
    static void glUseProgram(int a) { boundProgram = a; }
    static void glDeleteShader(int a) { SHADER.remove(a); }
    static void glDeleteProgram(int a) { PROGRAM.remove(a); }
    static int glGetShaderi(int a, int b) { return b == 0x8B81 ? 1 : 0; }
    static int glGetProgrami(int a, int b) { return b == 0x8B82 ? 1 : 0; }
    static String glGetShaderInfoLog(int a) { return ""; }
    static String glGetProgramInfoLog(int a) { return ""; }
    static int glGetUniformLocation(int a, CharSequence b) { return 0; }
    static void glUniform1i(int a, int b) {}
    static void glUniform1f(int a, float b) {}
    static void glUniform2f(int a, float b, float c) {}
    static void glUniform3f(int a, float b, float c, float d) {}
    static void glUniform4f(int a, float b, float c, float d, float e) {}
    static void glUniformMatrix4fv(int a, boolean b, FloatBuffer c) {}

    static int glGenVertexArrays() { int i = id(); VAO.put(i, 1); return i; }
    static void glGenVertexArrays(IntBuffer out) { while (out.remaining() > 0) out.put(glGenVertexArrays()); }
    static void glBindVertexArray(int a) { boundVao = a; }
    static void glDeleteVertexArrays(int a) { VAO.remove(a); }
    static int glGenFramebuffers() { int i = id(); FBO.put(i, 1); return i; }
    static void glGenFramebuffers(IntBuffer out) { while (out.remaining() > 0) out.put(glGenFramebuffers()); }
    static void glBindFramebuffer(int a, int b) { boundFbo = b; }
    static void glFramebufferTexture2D(int a, int b, int c, int d, int e) {}
    static int glCheckFramebufferStatus(int a) { return 0x8CD5; }
    static void glDeleteFramebuffers(int a) { FBO.remove(a); }
    static int glGenRenderbuffers() { int i = id(); RBO.put(i, 1); return i; }
    static void glBindRenderbuffer(int a, int b) { boundRbo = b; }
    static void glRenderbufferStorage(int a, int b, int c, int d) {}
    static void glFramebufferRenderbuffer(int a, int b, int c, int d) {}
    static void glDeleteRenderbuffers(int a) { RBO.remove(a); }
    static void glClipControl(int a, int b) {}
}
