/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Writes Retromod's notes about an old mod to the game's log, so they reach latest.log on every loader. */
public final class LegacyLog {

    private static final String PREFIX = "[Retromod] ";
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    // Plain levels rather than an enum, so the copy inside each mod stays one class.
    private static final int WARN = 0;
    private static final int INFO = 1;
    private static final int DEBUG = 2;

    private static volatile Object logger;
    private static volatile Method warnMethod;
    private static volatile Method infoMethod;
    private static volatile Method debugMethod;
    private static volatile boolean unavailable;

    private LegacyLog() {}

    /** Logs {@code message} at info level. */
    public static void info(String message) {
        write(INFO, message);
    }

    /** Logs {@code message} at debug level, which reaches the game's debug log. */
    public static void debug(String message) {
        write(DEBUG, message);
    }

    /** Logs {@code message} as a warning. */
    public static void warn(String message) {
        write(WARN, message);
    }

    /** Logs {@code message} as a warning the first time {@code key} is seen, and says whether it did. */
    public static boolean warnOnce(String key, String message) {
        if (!WARNED.add(key)) return false;
        warn(message);
        return true;
    }

    private static void write(int level, String message) {
        String line = PREFIX + message;
        if (findLogger()) {
            try {
                Method method = level == WARN ? warnMethod : level == INFO ? infoMethod : debugMethod;
                method.invoke(logger, line);
                return;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                // Standard error below still shows the line in the launcher console.
            }
        }
        System.err.println(line);
    }

    /** Finds the game's SLF4J logger once; Forge and NeoForge never copy standard error into latest.log. */
    private static boolean findLogger() {
        if (logger != null) return true;
        if (unavailable) return false;
        try {
            // Built at run time, so the release jar's SLF4J relocation cannot point it at Retromod's own copy.
            String factoryName = String.join(".", "org", "slf4j", "LoggerFactory");
            String loggerName = String.join(".", "org", "slf4j", "Logger");
            ClassLoader loader = LegacyLog.class.getClassLoader();
            Class<?> factory = Class.forName(factoryName, true, loader);
            // Called through the exported Logger interface, because the host's implementation class may not be.
            Class<?> loggerType = Class.forName(loggerName, false, loader);
            warnMethod = loggerType.getMethod("warn", String.class);
            infoMethod = loggerType.getMethod("info", String.class);
            debugMethod = loggerType.getMethod("debug", String.class);
            logger = factory.getMethod("getLogger", String.class).invoke(null, "Retromod");
            return logger != null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException missing) {
            unavailable = true;
            return false;
        }
    }
}
