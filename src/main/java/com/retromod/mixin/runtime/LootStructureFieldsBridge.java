/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/** Reads the loot table, pool and entry fields 26.3 rebuilt around holders back in their old shapes, for old accessors. */
public final class LootStructureFieldsBridge {
    private LootStructureFieldsBridge() {}

    /** {@code LootTable.functions} and {@code LootPool.functions}: the one modifier 26.3 keeps, as a list, or empty. */
    public static Object functions(Object owner) {
        Optional<?> modifier = (Optional<?>) read(owner, "modifier");
        return modifier.isPresent() ? List.of(call(modifier.get(), "net.minecraft.core.Holder", "value")) : List.of();
    }

    /** {@code LootPool.conditions}: the pool's one condition as a list, or empty. */
    public static Object conditions(Object pool) {
        Optional<?> condition = (Optional<?>) read(pool, "condition");
        return condition.isPresent() ? List.of(call(condition.get(), "net.minecraft.core.Holder", "value")) : List.of();
    }

    /** {@code TagEntry.expand}: the flag its parent class now holds. */
    public static Object expand(Object entry) {
        return read(entry, "expand");
    }

    /** {@code LootPool.rolls}: 26.3 rolls with an int provider holder, which the old NumberProvider type cannot carry. */
    public static Object rolls(Object pool) {
        throw new UnsupportedOperationException("LootPool.rolls is an int provider holder since Minecraft 26.3, "
                + "which a mod built for an older version cannot read; Retromod has no old NumberProvider for it");
    }

    /** {@code LootPool.bonusRolls}: as {@link #rolls}, for the float provider holder. */
    public static Object bonusRolls(Object pool) {
        throw new UnsupportedOperationException("LootPool.bonusRolls is a float provider holder since Minecraft 26.3, "
                + "which a mod built for an older version cannot read; Retromod has no old NumberProvider for it");
    }

    /** {@code TagEntry.tag}: the tag behind the entry's holder set, or null when the set lists items directly. */
    public static Object tag(Object entry) {
        return ((Optional<?>) call(read(entry, "tag"), "net.minecraft.core.HolderSet", "unwrapKey")).orElse(null);
    }

    /**
     * {@code NestedLootTable.contents}: the referenced table's key on the left, or an inline table on the right, read
     * from the first table of the holder set 26.3 keeps, or null when the set is empty.
     */
    public static Object contents(Object entry) {
        Object tables = read(entry, "value");
        if ((Integer) call(tables, "net.minecraft.core.HolderSet", "size") == 0) return null;
        ClassLoader game = entry.getClass().getClassLoader();
        Object holder = invoke(game, tables, "net.minecraft.core.HolderSet", "get", new Class<?>[] {int.class}, 0);
        Optional<?> key = (Optional<?>) call(holder, "net.minecraft.core.Holder", "unwrapKey");
        String side = key.isPresent() ? "left" : "right";
        Object value = key.isPresent() ? key.get() : call(holder, "net.minecraft.core.Holder", "value");
        // Either is the game's own copy of DataFixerUpper, which the entry's class loader sees.
        return invoke(game, null, "com.mojang.datafixers.util.Either", side, new Class<?>[] {Object.class}, value);
    }

    private static Object call(Object target, String type, String name) {
        return invoke(target.getClass().getClassLoader(), target, type, name, new Class<?>[0]);
    }

    private static Object invoke(ClassLoader loader, Object target, String type, String name, Class<?>[] parameters,
            Object... arguments) {
        try {
            // The host's own type, so the call reaches it whatever class implements it.
            Method method = Class.forName(type, false, loader).getMethod(name, parameters);
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not call " + type + "." + name + " on this Minecraft version", e);
        }
    }

    private static Object read(Object owner, String name) {
        try {
            return field(owner.getClass(), name).get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read " + name + " from "
                    + owner.getClass().getName() + " on this Minecraft version", e);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException next) {
                // Keep walking up: the field is declared on a parent.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
