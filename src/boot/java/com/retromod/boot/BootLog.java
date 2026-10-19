/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot;

import java.lang.reflect.Method;

/** Logging for bootstrap code, which may only reference the JDK. */
public final class BootLog {

    private static final String LOGGER_NAME = "Retromod Boot";

    private final Object logger;
    private final Method info;
    private final Method warn;
    private final Method error;

    private BootLog(Object logger, Method info, Method warn, Method error) {
        this.logger = logger;
        this.info = info;
        this.warn = warn;
        this.error = error;
    }

    /** A logger that writes to SLF4J when the host has it, otherwise to standard output. */
    public static BootLog create() {
        // Every Forge and NeoForge host ships SLF4J, so messages reach latest.log through it. It is
        // looked up by name so this class never links against SLF4J.
        ClassLoader loader = BootLog.class.getClassLoader();
        try {
            Class<?> factory = Class.forName("org.slf4j.LoggerFactory", true, loader);
            Class<?> loggerType = Class.forName("org.slf4j.Logger", false, loader);
            Object logger = factory.getMethod("getLogger", String.class)
                    .invoke(null, LOGGER_NAME);
            return new BootLog(logger,
                    loggerType.getMethod("info", String.class),
                    loggerType.getMethod("warn", String.class),
                    loggerType.getMethod("error", String.class));
        } catch (Throwable unavailable) {
            // Without it, messages go to standard output, which the loaders also capture.
            return new BootLog(null, null, null, null);
        }
    }

    /** A logger that only writes to standard output, for tests. */
    static BootLog standardOutput() {
        return new BootLog(null, null, null, null);
    }

    public void info(String message) {
        write(info, "INFO", message);
    }

    public void warn(String message) {
        write(warn, "WARN", message);
    }

    public void error(String message) {
        write(error, "ERROR", message);
    }

    private void write(Method method, String level, String message) {
        if (logger != null && method != null) {
            try {
                method.invoke(logger, message);
                return;
            } catch (Throwable ignored) {
                // Fall through to standard output so the message is never lost.
            }
        }
        System.out.println("[" + LOGGER_NAME + "/" + level + "] " + message);
    }
}
