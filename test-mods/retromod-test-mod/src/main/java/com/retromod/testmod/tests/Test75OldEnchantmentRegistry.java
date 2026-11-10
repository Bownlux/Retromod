/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** The enchantment registry a 1.20.1 mod reads, which 1.21 moved into each world's data packs. */
public final class Test75OldEnchantmentRegistry implements Test {

    @Override
    public String description() {
        return "Registries.ENCHANTMENT finds sharpness, names it back, and enchants an item";
    }

    @Override
    public TestResult run() {
        Enchantment sharpness = Registries.ENCHANTMENT.get(new Identifier("minecraft", "sharpness"));
        if (sharpness == null) return TestResult.fail("sharpness was not found once the server started");
        Identifier id = Registries.ENCHANTMENT.getId(sharpness);
        if (id == null || !id.toString().equals("minecraft:sharpness")) {
            return TestResult.fail("sharpness read back as " + id);
        }
        // 1.21 made addEnchantment take the enchantment's holder in the world.
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.addEnchantment(sharpness, 2);
        return sword.hasEnchantments() ? TestResult.success() : TestResult.fail("the sword was not enchanted");
    }
}
