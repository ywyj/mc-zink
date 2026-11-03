package com.dsh.dshcore.arfix;

/** dshcore AR fix 总开关（默认关闭；只读系统属性/环境变量，不触碰配置系统）。 */
public final class ArFixGate {
    private static final boolean NO_READBACK = readNoReadback();

    private ArFixGate() {
    }

    public static boolean noReadback() {
        return NO_READBACK;
    }

    private static boolean readNoReadback() {
        return truthy(System.getProperty("dsh.arfix.noReadback"))
                || truthy(System.getenv("DSH_AR_NO_READBACK"));
    }

    private static boolean truthy(String v) {
        if (v == null) return false;
        String s = v.trim();
        return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on");
    }
}
