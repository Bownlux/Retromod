/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.item;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** The property functions old mods registered, shared by every copy of the rebuilt ItemProperties. */
public final class LegacyItemPropertyStore {

    // Each translated jar has its own copy, and Retromod's item property type is yet another, so the table holds
    // only JDK types: items by identity, ids as text, each function behind a ToDoubleFunction of its four arguments.
    private static final String SLOT = "minecraft.itemProperties";
    private static final int PROTOCOL = 1;
    // The key of the properties every item has; no item equals a string.
    private static final String GENERIC = "generic";

    private LegacyItemPropertyStore() {}

    /** Records an item's property; a later one for the same item and id replaces it, as the old map did. */
    public static void record(Item item, String id, ItemPropertyFunction function) {
        // A property on no item could never be asked for.
        if (item == null || function == null) return;
        put(item, id, function);
    }

    /** Records a property every item has. */
    public static void recordGeneric(String id, ItemPropertyFunction function) {
        if (function == null) return;
        put(GENERIC, id, function);
    }

    /** The function for an item's property, called with {@code {stack, level, entity, seed}}; null for none. */
    public static ToDoubleFunction<Object[]> find(Object item, String id) {
        Map<Object, Map<String, ToDoubleFunction<Object[]>>> table = table();
        // Minecraft 1.20.1 asked the properties every item has before the item's own.
        Map<String, ToDoubleFunction<Object[]>> generic = table.get(GENERIC);
        ToDoubleFunction<Object[]> found = generic == null ? null : generic.get(id);
        if (found != null || item == null) return found;
        Map<String, ToDoubleFunction<Object[]>> own = table.get(item);
        return own == null ? null : own.get(id);
    }

    /** The registered function as this copy's type, or null; one another copy recorded is wrapped. */
    public static ItemPropertyFunction function(Item item, String id) {
        ToDoubleFunction<Object[]> found = find(item, id);
        if (found == null) return null;
        if (found instanceof Recorded recorded) return recorded.function();
        return (stack, level, entity, seed) -> (float) found.applyAsDouble(new Object[]{stack, level, entity, seed});
    }

    private static void put(Object key, String id, ItemPropertyFunction function) {
        table().computeIfAbsent(key, unused -> new ConcurrentHashMap<>()).put(id, new Recorded(function));
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Map<String, ToDoubleFunction<Object[]>>> table() {
        return LegacySharedSlots.get(SLOT, PROTOCOL, Map.class, ConcurrentHashMap::new);
    }

    /** A function this copy recorded, callable by any copy through the JDK interface. */
    private record Recorded(ItemPropertyFunction function) implements ToDoubleFunction<Object[]> {
        @Override
        public double applyAsDouble(Object[] arguments) {
            return function.call((ItemStack) arguments[0], (ClientLevel) arguments[1], (LivingEntity) arguments[2],
                    (Integer) arguments[3]);
        }
    }
}
