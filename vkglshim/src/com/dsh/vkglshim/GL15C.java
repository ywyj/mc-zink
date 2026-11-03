package com.dsh.vkglshim;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public final class GL15C {
    public static int glGenBuffers() { return Shim.glGenBuffers(); }
    public static void glGenBuffers(IntBuffer a0) { Shim.glGenBuffers(a0); }
    public static void glBindBuffer(int a0, int a1) { Shim.glBindBuffer(a0, a1); }
    public static void glBufferData(int a0, ByteBuffer a1, int a2) { Shim.glBufferData(a0, a1, a2); }
    public static void glBufferSubData(int a0, long a1, ByteBuffer a2) { Shim.glBufferSubData(a0, a1, a2); }
    public static void glDeleteBuffers(int a0) { Shim.glDeleteBuffers(a0); }
    public static void glDeleteBuffers(IntBuffer a0) { Shim.glDeleteBuffers(a0); }
}
