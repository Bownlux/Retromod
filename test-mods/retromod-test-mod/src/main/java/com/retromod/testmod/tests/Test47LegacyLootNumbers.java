/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.function.SetCountLootFunction;
import net.minecraft.loot.provider.number.BinomialLootNumberProvider;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.loot.provider.number.LootNumberProvider;
import net.minecraft.loot.provider.number.UniformLootNumberProvider;

/**
 * #340: 26.3 removed the loot NumberProvider interface with ConstantValue and UniformGenerator, and
 * its loot pools and count functions take int or float providers instead. A class that only named
 * the old interface in a descriptor failed to load, as Chef's Delight's trade sets did.
 */
public final class Test47LegacyLootNumbers implements Test {

    @Override
    public String description() {
        return "#340 loot numbers built in code reach a loot pool and a set count function";
    }

    @Override
    public TestResult run() {
        return LootCalls.build() ? TestResult.success()
                : TestResult.fail("a loot pool or set count builder came back empty");
    }

    /** Holds the calls, so a type a host lacks fails this test instead of loading the suite. */
    private static final class LootCalls {
        static boolean build() {
            LootPool.Builder pool = LootPool.builder()
                    .rolls(passedAsTheOldInterface(ConstantLootNumberProvider.create(2.0F)))
                    .bonusRolls(UniformLootNumberProvider.create(0.0F, 1.0F));
            pool.rolls(BinomialLootNumberProvider.create(3, 0.5F));
            Object count = SetCountLootFunction.builder(UniformLootNumberProvider.create(1.0F, 3.0F), true);
            return pool.build() != null && count != null;
        }

        /** A mod helper typed with the removed interface, like Chef's Delight's register(). */
        private static LootNumberProvider passedAsTheOldInterface(LootNumberProvider provider) {
            return provider;
        }
    }
}
