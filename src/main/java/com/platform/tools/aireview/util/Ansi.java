package com.platform.tools.aireview.util;

/** Minimal ANSI helper. Honours a global enable flag (config + TTY). */
public final class Ansi {
    private static volatile boolean enabled = false;

    private Ansi() {}

    public static void setEnabled(boolean on) { enabled = on; }
    public static boolean enabled() { return enabled; }

    public static String c(String code, String s) {
        return enabled ? "[" + code + "m" + s + "[0m" : s;
    }
    public static String bold(String s)   { return c("1", s); }
    public static String dim(String s)    { return c("2", s); }
    public static String red(String s)    { return c("31", s); }
    public static String yellow(String s) { return c("33", s); }
    public static String green(String s)  { return c("32", s); }
    public static String cyan(String s)   { return c("36", s); }
}
