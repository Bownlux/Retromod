/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.item;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Minecraft 1.20.1's item property registry, which 1.21.4 removed; Retromod's item property type reads what it records. */
public class ItemProperties {

    public ItemProperties() {}

    /** Forge's and NeoForge's public register, which a property for one item went through. */
    public static void register(Item item, Identifier id, ItemPropertyFunction function) {
        LegacyItemPropertyStore.record(item, id.toString(), function);
    }

    /** The vanilla register, which Fabric mods and access-transformed Forge mods called. */
    public static void register(Item item, Identifier id, ClampedItemPropertyFunction function) {
        LegacyItemPropertyStore.record(item, id.toString(), function);
    }

    /** A property every item has, which Minecraft 1.20.1 asked before an item's own. */
    public static ItemPropertyFunction registerGeneric(Identifier id, ItemPropertyFunction function) {
        LegacyItemPropertyStore.recordGeneric(id.toString(), function);
        return function;
    }

    /** The vanilla registerGeneric, which returned the clamped function it was given. */
    public static ClampedItemPropertyFunction registerGeneric(Identifier id, ClampedItemPropertyFunction function) {
        LegacyItemPropertyStore.recordGeneric(id.toString(), function);
        return function;
    }

    /** Fabric API's generic register, which returned nothing. */
    public static void fabricRegisterGeneric(Identifier id, ClampedItemPropertyFunction function) {
        LegacyItemPropertyStore.recordGeneric(id.toString(), function);
    }

    /** The function an old mod registered for the item's property, or null; Minecraft's own properties are not here. */
    public static ItemPropertyFunction getProperty(Item item, Identifier id) {
        return LegacyItemPropertyStore.function(item, id.toString());
    }

    /** Minecraft 1.21.1's lookup by stack. */
    public static ItemPropertyFunction getProperty(ItemStack stack, Identifier id) {
        return getProperty(stack.getItem(), id);
    }
}
