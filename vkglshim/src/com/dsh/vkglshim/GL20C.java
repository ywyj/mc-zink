package com.dsh.vkglshim;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public final class GL20C {
    public static int glCreateShader(int a0) { return Shim.glCreateShader(a0); }
    public static void glShaderSource(int a0, CharSequence a1) { Shim.glShaderSource(a0, a1); }
    public static void glCompileShader(int a0) { Shim.glCompileShader(a0); }
    public static int glCreateProgram() { return Shim.glCreateProgram(); }
    public static void glAttachShader(int a0, int a1) { Shim.glAttachShader(a0, a1); }
    public static void glLinkProgram(int a0) { Shim.glLinkProgram(a0); }
    public static void glUseProgram(int a0) { Shim.glUseProgram(a0); }
    public static void glDeleteShader(int a0) { Shim.glDeleteShader(a0); }
    public static void glDeleteProgram(int a0) { Shim.glDeleteProgram(a0); }
    public static int glGetShaderi(int a0, int a1) { return Shim.glGetShaderi(a0, a1); }
    public static int glGetProgrami(int a0, int a1) { return Shim.glGetProgrami(a0, a1); }
    public static String glGetShaderInfoLog(int a0) { return Shim.glGetShaderInfoLog(a0); }
    public static String glGetProgramInfoLog(int a0) { return Shim.glGetProgramInfoLog(a0); }
    public static int glGetUniformLocation(int a0, CharSequence a1) { return Shim.glGetUniformLocation(a0, a1); }
    public static void glUniform1i(int a0, int a1) { Shim.glUniform1i(a0, a1); }
    public static void glUniform1f(int a0, float a1) { Shim.glUniform1f(a0, a1); }
    public static void glUniform2f(int a0, float a1, float a2) { Shim.glUniform2f(a0, a1, a2); }
    public static void glUniform3f(int a0, float a1, float a2, float a3) { Shim.glUniform3f(a0, a1, a2, a3); }
    public static void glUniform4f(int a0, float a1, float a2, float a3, float a4) { Shim.glUniform4f(a0, a1, a2, a3, a4); }
    public static void glUniformMatrix4fv(int a0, boolean a1, FloatBuffer a2) { Shim.glUniformMatrix4fv(a0, a1, a2); }
}
