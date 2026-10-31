/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

/** {@code Item.toString()} as a mod built before 1.20.5 read it: the id's path, without the namespace. */
public final class LegacyItemNames {

    // Resolved once from this class's loader, which is the mod's and sees Minecraft.
    private static volatile Class<?> itemClass;
    private static volatile boolean resolved;

    private LegacyItemNames() {}

    /** {@code value.toString()}: an item's path, as 1.20.4 and older returned it, else unchanged. */
    public static String toString(Object value) {
        String text = value.toString();
        if (!isItem(value)) return text;
        int colon = text.indexOf(':');
        return colon < 0 ? text : text.substring(colon + 1);
    }

    /** {@code String.valueOf(value)}, with the same item rule. */
    public static String valueOf(Object value) {
        return value == null ? "null" : toString(value);
    }

    private static boolean isItem(Object value) {
        if (!resolved) {
            try {
                itemClass = Class.forName("net.minecraft.world.item.Item", false, LegacyItemNames.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                itemClass = null;
            }
            resolved = true;
        }
        Class<?> item = itemClass;
        return item != null && item.isInstance(value);
    }
}
