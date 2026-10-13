/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

/**
 * Stands in for {@code Enchantment.Rarity}, which 1.21 replaced with a weight and an anvil cost on
 * the enchantment's data. Each rarity keeps its old weight, and the anvil cost is the one 1.21
 * gave the vanilla enchantments of that rarity.
 */
public final class LegacyEnchantmentRarity {
    public static final LegacyEnchantmentRarity COMMON = new LegacyEnchantmentRarity("COMMON", 10, 1);
    public static final LegacyEnchantmentRarity UNCOMMON = new LegacyEnchantmentRarity("UNCOMMON", 5, 2);
    public static final LegacyEnchantmentRarity RARE = new LegacyEnchantmentRarity("RARE", 2, 4);
    public static final LegacyEnchantmentRarity VERY_RARE = new LegacyEnchantmentRarity("VERY_RARE", 1, 8);

    private final String name;
    private final int weight;
    private final int anvilCost;

    private LegacyEnchantmentRarity(String name, int weight, int anvilCost) {
        this.name = name;
        this.weight = weight;
        this.anvilCost = anvilCost;
    }

    public int getWeight() { return weight; }

    public int anvilCost() { return anvilCost; }

    public String name() { return name; }

    public static LegacyEnchantmentRarity[] values() {
        return new LegacyEnchantmentRarity[] {COMMON, UNCOMMON, RARE, VERY_RARE};
    }

    @Override
    public String toString() { return name; }
}
