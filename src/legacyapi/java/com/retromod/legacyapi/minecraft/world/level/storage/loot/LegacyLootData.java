/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.storage.loot;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootTable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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

    /** {@code server.getLootData()}. */
    public static LegacyLootData of(MinecraftServer server) {
        return new LegacyLootData(server);
    }

    /** {@code getLootTable(id)}: the empty table for an id the server does not know, as before. */
    public LootTable getLootTable(Identifier id) {
        return server.reloadableRegistries().getLootTable(key(id));
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
