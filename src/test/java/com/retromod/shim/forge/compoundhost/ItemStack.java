/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.compoundhost;

import java.util.List;

/** Stands in for ItemStack: an item and its components, written as text. */
public final class ItemStack {
    private final Item item;
    private final List<String> components;

    private ItemStack(Item item, List<String> components) {
        this.item = item;
        this.components = components;
    }

    public static ItemStack of(String item, String... components) {
        return new ItemStack(new Item(item), List.of(components));
    }

    public Item item() {
        return item;
    }

    public Holder<Item> typeHolder() {
        return new DirectHolder<>(item);
    }

    public static boolean isSameItemSameComponents(ItemStack first, ItemStack second) {
        return first.item.equals(second.item) && first.components.equals(second.components);
    }
}
