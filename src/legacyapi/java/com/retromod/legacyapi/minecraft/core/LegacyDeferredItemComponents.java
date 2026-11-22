/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.core;

import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Gives an item built under a placeholder id the name and model components of its real id, on 1.21.11 and on 26.1 and newer. */
final class LegacyDeferredItemComponents {

    // Looked up once: the item's component map field (1.21.11 only), and the queue's list and entry fields (26.1 and newer).
    private static volatile boolean componentsChecked;
    private static volatile Field components;
    private static volatile Field queueList;
    private static volatile Field entryKey;
    private static volatile Field entryInitializer;

    private LegacyDeferredItemComponents() {}

    /** Sets the name and model the real id gives, where the host keeps the item's components. */
    static void settle(Item item, ResourceKey<Item> placeholder, ResourceKey<Item> key, String description, Identifier model)
            throws ReflectiveOperationException {
        Component name = Component.translatable(description);
        Field components = componentsField();
        if (components != null) {
            // 1.21.11 builds the item's components in its constructor.
            DataComponentMap rebuilt = DataComponentMap.builder().addAll(item.components())
                    .set(DataComponents.ITEM_NAME, name).set(DataComponents.ITEM_MODEL, model).build();
            components.set(item, rebuilt);
        } else {
            // 26.1 and newer queue them by key and build them once the registries load.
            moveQueued(placeholder, key, name, model);
        }
    }

    /** Item's own component map, which only 1.21.11 keeps; null on 26.1 and newer. */
    private static Field componentsField() {
        if (componentsChecked) return components;
        try {
            Field field = Item.class.getDeclaredField("components");
            components = field.getType() == DataComponentMap.class ? accessible(field) : null;
        } catch (NoSuchFieldException absent) {
            components = null;
        }
        componentsChecked = true;
        return components;
    }

    /** Requeues every initializer kept under {@code placeholder} under {@code key}, then sets the name and model the real id gives. */
    @SuppressWarnings("unchecked")
    static void moveQueued(ResourceKey<Item> placeholder, ResourceKey<Item> key, Component name, Identifier model)
            throws ReflectiveOperationException {
        DataComponentInitializers queue = BuiltInRegistries.DATA_COMPONENT_INITIALIZERS;
        // The queue is a private list of (key, initializer) records, and a key it cannot find in the item registry
        // stops the whole component build, so the placeholder entry has to leave it.
        Field list = queueList;
        if (list == null) queueList = list = accessible(DataComponentInitializers.class.getDeclaredField("initializers"));
        List<Object> entries = (List<Object>) list.get(queue);
        List<DataComponentInitializers.Initializer<Item>> moved = new ArrayList<>();
        for (Iterator<Object> it = entries.iterator(); it.hasNext(); ) {
            Object entry = it.next();
            if (entryKey == null) {
                entryKey = accessible(entry.getClass().getDeclaredField("key"));
                entryInitializer = accessible(entry.getClass().getDeclaredField("initializer"));
            }
            if (!placeholder.equals(entryKey.get(entry))) continue;
            moved.add((DataComponentInitializers.Initializer<Item>) entryInitializer.get(entry));
            it.remove();
        }
        if (moved.isEmpty()) return;
        for (DataComponentInitializers.Initializer<Item> initializer : moved) queue.add(key, initializer);
        // The moved initializer still sets the name and model it was built with; this one runs after it.
        queue.add(key, (builder, lookup, ignored) -> builder.set(DataComponents.ITEM_NAME, name).set(DataComponents.ITEM_MODEL, model));
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }
}
