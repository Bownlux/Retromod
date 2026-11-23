/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.fml;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/** Forge's {@code registerExtensionPoint} on NeoForge, filing a Forge config screen factory under NeoForge's key. */
public final class LegacyExtensionPoints {

    // Compared by name, so a dedicated server never loads the client-package factory or NeoForge's key for it.
    static final String FORGE_SCREEN_FACTORY = "LegacyConfigScreenFactory";
    static final String NEOFORGE_SCREEN_FACTORY = "net.neoforged.neoforge.client.gui.IConfigScreenFactory";

    private LegacyExtensionPoints() {}

    /** {@code owner.registerExtensionPoint(type, supplier)}: every point other than the config screen is registered as it was. */
    public static void registerExtensionPoint(Object owner, Class<?> type, Supplier<?> supplier) {
        try {
            Class<?> key = type;
            if (type.getName().endsWith("." + FORGE_SCREEN_FACTORY)) {
                key = Class.forName(NEOFORGE_SCREEN_FACTORY, false, type.getClassLoader());
            }
            Method register = owner.getClass().getMethod("registerExtensionPoint", Class.class, Supplier.class);
            register.invoke(owner, key, supplier);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not register the " + type.getName() + " extension point on " + owner, e);
        }
    }
}
