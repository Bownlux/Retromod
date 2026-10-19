/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.BinomialDistributionGenerator;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

/**
 * #340: 26.3 removed the loot NumberProvider interface with ConstantValue and UniformGenerator, and
 * its loot pools and count functions take int or float providers instead. A class that only named
 * the old interface in a descriptor failed to load, as Chef's Delight's trade sets did. Kept out
 * of the mod class, so a type a host lacks fails only its own check instead of the whole mod.
 */
final class LootNumbers {

    private LootNumbers() {}

    static boolean poolAndCountBuild() {
        LootPool.Builder pool = LootPool.lootPool()
                .setRolls(passedAsTheOldInterface(ConstantValue.exactly(2.0F)))
                .setBonusRolls(UniformGenerator.between(0.0F, 1.0F));
        pool.setRolls(BinomialDistributionGenerator.binomial(3, 0.5F));
        Object count = SetItemCountFunction.setCount(UniformGenerator.between(1.0F, 3.0F), true);
        return pool.build() != null && count != null;
    }

    /** A mod helper typed with the removed interface, like Chef's Delight's register(). */
    private static NumberProvider passedAsTheOldInterface(NumberProvider provider) {
        return provider;
    }
}
