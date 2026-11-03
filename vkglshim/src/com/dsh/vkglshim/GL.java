package com.dsh.vkglshim;

public final class GL {
    private static GLCapabilities caps;
    public static GLCapabilities getCapabilities() { if (caps == null) caps = new GLCapabilities(); return caps; }
    public static GLCapabilities createCapabilities() { caps = new GLCapabilities(); return caps; }
    public static GLCapabilities createCapabilities(boolean dsa) { caps = new GLCapabilities(); return caps; }
    public static void setCapabilities(GLCapabilities c) { caps = c; }
    public static void setCapabilities(GLCapabilities c, boolean dsa) { caps = c; }
    public static int getError() { return 0; }
}
