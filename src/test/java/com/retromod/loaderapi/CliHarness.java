/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import com.retromod.cli.RetromodCli;
import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.ShimRegistry;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the CLI {@code transform} and {@code aot} commands in this JVM, as {@code main} would. */
public final class CliHarness {

    private CliHarness() {}

    /** Runs {@code transform <input> --output <output>} for {@code target}. */
    public static void transform(Path input, Path output, String target) throws Exception {
        Object[] saved = save();
        try {
            prepare(target);
            invoke("transformCommand", new String[]{"transform", input.toString(), "--output",
                    output.toString()});
        } finally {
            restore(saved);
        }
    }

    /** Runs {@code aot <input> --output <output>} for {@code target}, without a stale cache. */
    public static void aot(Path input, Path output, String target) throws Exception {
        Object[] saved = save();
        Path cached = Path.of("config", "retromod", "aot-cache",
                input.getFileName().toString().replace(".jar", "-aot.jar"));
        Files.deleteIfExists(cached);
        try {
            prepare(target);
            invoke("aotCommand", new String[]{"aot", input.toString(), "--output", output.toString()});
        } finally {
            Files.deleteIfExists(cached);
            restore(saved);
        }
    }

    /** The CLI's static state, so later tests see the defaults main would set. */
    private static final String[] STATE = {"shimRegistry", "detector", "TARGET_MC_VERSION",
            "targetMcVersionExplicit"};

    private static Object[] save() throws ReflectiveOperationException, java.io.IOException {
        Object[] values = new Object[STATE.length + 2];
        for (int i = 0; i < STATE.length; i++) {
            Field field = RetromodCli.class.getDeclaredField(STATE[i]);
            field.setAccessible(true);
            values[i] = field.get(null);
        }
        values[STATE.length] = RetromodVersion.TARGET_MC_VERSION;
        // Without --mc-jar the CLI indexes an installed Minecraft found under user.home. An empty
        // home keeps the output the same on every machine and leaves no index behind.
        values[STATE.length + 1] = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("retromod-cli-home").toString());
        return values;
    }

    private static void restore(Object[] values) throws ReflectiveOperationException {
        for (int i = 0; i < STATE.length; i++) set(STATE[i], values[i]);
        RetromodVersion.TARGET_MC_VERSION = (String) values[STATE.length];
        System.setProperty("user.home", (String) values[STATE.length + 1]);
    }

    private static void prepare(String target) throws Exception {
        set("shimRegistry", new ShimRegistry());
        set("detector", new ModVersionDetector());
        set("TARGET_MC_VERSION", target);
        set("targetMcVersionExplicit", true);
        RetromodVersion.TARGET_MC_VERSION = target;
        Method register = RetromodCli.class.getDeclaredMethod("registerAllShims");
        register.setAccessible(true);
        register.invoke(null);
    }

    private static void set(String name, Object value) throws ReflectiveOperationException {
        Field field = RetromodCli.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void invoke(String command, String[] args) throws Exception {
        Method method = RetromodCli.class.getDeclaredMethod(command, String[].class);
        method.setAccessible(true);
        try {
            method.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw new IOException(e.getCause());
        }
    }
}
