/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge.embedded;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.function.Supplier;

/** Runs NeoForge's removed {@code Properties.lootFrom(block)} as the loot table override that replaced it. */
public final class LegacyLootFrom {

    private LegacyLootFrom() {}

    /** {@code properties.lootFrom(block)}: the block's own loot table, named without building the block. */
    public static Object lootFrom(Object properties, Supplier<?> block) {
        try {
            ClassLoader loader = LegacyLootFrom.class.getClassLoader();
            Class<?> keyClass = Class.forName("net.minecraft.resources.ResourceKey", true, loader);
            Optional<?> table;
            try {
                // A deferred holder knows its id before the block is built; lootFrom read the block lazily.
                Object id = block.getClass().getMethod("getId").invoke(block);
                Object path = id.getClass().getMethod("withPrefix", String.class).invoke(id, "blocks/");
                Object lootTables = Class.forName("net.minecraft.core.registries.Registries", true, loader)
                        .getField("LOOT_TABLE").get(null);
                table = Optional.of(keyClass.getMethod("create", keyClass, id.getClass()).invoke(null, lootTables, path));
            } catch (NoSuchMethodException plainSupplier) {
                Object built = block.get();
                table = (Optional<?>) built.getClass().getMethod("getLootTable").invoke(built);
            }
            return properties.getClass().getMethod("overrideLootTable", Optional.class).invoke(properties, table);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not set a block's loot table from another block", failure);
        }
    }
}
