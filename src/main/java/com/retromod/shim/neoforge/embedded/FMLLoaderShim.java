/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.neoforge.embedded;

import java.lang.reflect.Method;

/**
 * Reaches FML state that used to be static and now hangs off a loader instance.
 *
 * <p>{@code FMLLoader.getDist()} and {@code FMLLoader.getLoadingModList()} were static. Current
 * NeoForge keeps a loader instance behind {@code FMLLoader.getCurrent()} and makes both of them
 * instance methods, so a mod compiled against the old shape fails at its first call with
 * {@code IncompatibleClassChangeError: Expected static method}. Nothing about the call site says
 * this, which is why the crash names a method that plainly exists (#248).
 *
 * <p>The methods are looked up reflectively because Retromod is built without NeoForge on the
 * classpath and has to load on every loader it supports.
 */
public final class FMLLoaderShim {

    private static volatile Method current;
    private static volatile boolean resolved;

    private FMLLoaderShim() {}

    /** The dist of the running loader, for a mod that still calls the old static accessor. */
    public static Object getDist() {
        return callOnCurrentLoader("getDist");
    }

    /** The loading mod list, for a mod that still calls the old static accessor. */
    public static Object getLoadingModList() {
        return callOnCurrentLoader("getLoadingModList");
    }

    private static Object callOnCurrentLoader(String name) {
        Method accessor = currentAccessor();
        if (accessor == null) {
            throw new IllegalStateException(
                    "Retromod: FMLLoader.getCurrent() is unavailable, so " + name
                            + "() cannot be reached on this NeoForge build.");
        }
        try {
            Object loader = accessor.invoke(null);
            if (loader == null) {
                throw new IllegalStateException(
                        "Retromod: FMLLoader.getCurrent() returned nothing, so " + name
                                + "() was called before the loader was ready.");
            }
            return loader.getClass().getMethod(name).invoke(loader);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Retromod: could not read FMLLoader." + name + "() on this NeoForge build.", e);
        }
    }

    private static Method currentAccessor() {
        if (!resolved) {
            synchronized (FMLLoaderShim.class) {
                if (!resolved) {
                    try {
                        current = Class.forName("net.neoforged.fml.loading.FMLLoader")
                                .getMethod("getCurrent");
                    } catch (ReflectiveOperationException e) {
                        current = null;
                    }
                    resolved = true;
                }
            }
        }
        return current;
    }
}
