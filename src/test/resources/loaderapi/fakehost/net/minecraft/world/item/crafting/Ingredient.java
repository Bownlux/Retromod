package net.minecraft.world.item.crafting;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.stream.Stream;

public final class Ingredient {
    private final List<Item> items;
    private Ingredient(List<Item> items) { this.items = items; }

    public static Ingredient of(Item... items) { return new Ingredient(List.of(items)); }

    public Stream<Holder<Item>> items() { return items.stream().map(Holder::direct); }
}
