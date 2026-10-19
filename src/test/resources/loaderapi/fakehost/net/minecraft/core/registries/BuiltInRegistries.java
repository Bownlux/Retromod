package net.minecraft.core.registries;

import net.minecraft.core.DefaultedRegistry;
import net.minecraft.world.item.Item;

public final class BuiltInRegistries {
    public static final DefaultedRegistry<Item> ITEM = new DefaultedRegistry<>();
    private BuiltInRegistries() {}
}
