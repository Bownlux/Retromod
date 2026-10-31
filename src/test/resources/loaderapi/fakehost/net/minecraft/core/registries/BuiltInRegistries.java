package net.minecraft.core.registries;

import net.minecraft.core.DefaultedRegistry;
import net.minecraft.world.item.Item;

public final class BuiltInRegistries {
    public static final DefaultedRegistry<Item> ITEM = new DefaultedRegistry<>();
    public static final DefaultedRegistry<net.minecraft.world.entity.npc.villager.VillagerProfession> VILLAGER_PROFESSION =
            new DefaultedRegistry<>();
    public static final net.minecraft.core.Registry<net.minecraft.world.item.alchemy.Potion> POTION = new DefaultedRegistry<>();
    private BuiltInRegistries() {}
}
