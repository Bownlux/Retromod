/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.storage.loot;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/** Stands in for {@code LootDataManager}, which 1.20.5 removed: a server's loot tables, looked up by id. */
public final class LegacyLootData {

    // Each trigger class's trigger(player, table key), found once.
    private static final ClassValue<Method> TRIGGERS = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            for (Method method : type.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("trigger") && parameters.length == 2
                        && parameters[0] == ServerPlayer.class && parameters[1] == ResourceKey.class) {
                    return method;
                }
            }
            return null;
        }
    };

    private final MinecraftServer server;

    private LegacyLootData(MinecraftServer server) {
        this.server = server;
    }

    /** {@code new LootDataManager()}, which some mods keep as a placeholder: with no server it holds no data. */
    public LegacyLootData() {
        this(null);
    }

    /** {@code server.getLootData()}. */
    public static LegacyLootData of(MinecraftServer server) {
        return new LegacyLootData(server);
    }

    /** {@code getLootTable(id)}: the empty table for an id the server does not know, as before. */
    public LootTable getLootTable(Identifier id) {
        return server == null ? LootTable.EMPTY : server.reloadableRegistries().getLootTable(key(id));
    }

    /** {@code getElement(type, id)}: the loot table, predicate or item modifier loaded under {@code id}, or null. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object getElement(LootDataType<?> type, Identifier id) {
        HolderGetter<?> elements = elements(type);
        if (elements == null || id == null) return null;
        ResourceKey key = ResourceKey.create((ResourceKey) type.registryKey(), id);
        return ((HolderGetter) elements).get(key).map(holder -> ((Holder<?>) holder).value()).orElse(null);
    }

    /** {@code getKeys(type)}: the ids of every loaded element of that type. */
    public Collection<Identifier> getKeys(LootDataType<?> type) {
        if (!(elements(type) instanceof HolderLookup<?> lookup)) return List.of();
        return lookup.listElementIds().map(ResourceKey::identifier).toList();
    }

    // The loaded elements of one type, or null without a server.
    private HolderGetter<?> elements(LootDataType<?> type) {
        if (server == null || type == null) return null;
        try {
            // lookup() returns a HolderGetter.Provider before 1.21.2 and a HolderLookup.Provider after, so it is found by name.
            Object registries = server.reloadableRegistries();
            Object provider = registries.getClass().getMethod("lookup").invoke(registries);
            if (!(provider instanceof HolderGetter.Provider getters)) return null;
            return getters.lookup(type.registryKey()).orElse(null) instanceof HolderGetter<?> elements ? elements : null;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    /** {@code CriteriaTriggers.GENERATE_LOOT.trigger(player, id)}, which now takes the table's key. */
    public static void trigger(Object trigger, ServerPlayer player, Identifier id) {
        // The trigger's package moved in 26.1 and again in 26.2, so it is reached by name.
        Method method = TRIGGERS.get(trigger.getClass());
        if (method == null) throw new IllegalStateException("Retromod found no trigger(player, table) on " + trigger);
        try {
            method.invoke(trigger, player, key(id));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Retromod could not run " + method, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not run " + method, e);
        }
    }

    private static ResourceKey<LootTable> key(Identifier id) {
        return ResourceKey.create(Registries.LOOT_TABLE, id);
    }
}
