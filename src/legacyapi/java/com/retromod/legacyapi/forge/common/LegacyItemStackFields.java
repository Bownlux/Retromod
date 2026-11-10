/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common;

import java.lang.reflect.Field;

/** Answers a reflective lookup of Forge 1.20.1's {@code ItemStack.capNBT} on a Forge host that removed the field. */
public final class LegacyItemStackFields {

    /** Stands in for {@code capNBT}. It is static and always null, so reading it for any stack finds no capability data. */
    public static Object capNBT;

    private LegacyItemStackFields() {}

    /** {@code owner.getDeclaredField(name)}, which answers the stand-in when ItemStack has no {@code capNBT}. */
    public static Field getDeclaredField(Class<?> owner, String name) throws NoSuchFieldException {
        try {
            return owner.getDeclaredField(name);
        } catch (NoSuchFieldException missing) {
            if (!"capNBT".equals(name) || !"net.minecraft.world.item.ItemStack".equals(owner.getName())) throw missing;
            // Forge 26.2 keeps capability data off the stack, so a stack has none of its own to compare.
            return LegacyItemStackFields.class.getField("capNBT");
        }
    }
}
