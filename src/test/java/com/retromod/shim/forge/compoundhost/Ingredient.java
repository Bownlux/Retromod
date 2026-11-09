/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.compoundhost;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Stands in for Forge 26.2's patched Ingredient, which matches the items it names. */
public class Ingredient implements Predicate<ItemStack> {
    private final List<Item> items;

    protected Ingredient() {
        this(List.of());
    }

    Ingredient(List<Item> items) {
        this.items = items;
    }

    public static Ingredient ofItems(String... names) {
        return new Ingredient(Stream.of(names).map(Item::new).toList());
    }

    public Stream<Holder<Item>> items() {
        return items.stream().map(DirectHolder::new);
    }

    @Override
    public boolean test(ItemStack stack) {
        return stack != null && items.contains(stack.item());
    }
}
