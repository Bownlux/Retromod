/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

/** A 1.21.1 loot modifier: built from a condition array, with its codec started from the old codecStart. */
final class LegacyLootModifierCase {

    static final class Drops extends LootModifier {
        static final MapCodec<Drops> CODEC = RecordCodecBuilder.mapCodec(instance -> codecStart(instance).apply(instance, Drops::new));

        Drops(LootItemCondition[] conditions) {
            super(conditions);
        }

        @Override
        protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
            return loot;
        }

        @Override
        public MapCodec<? extends IGlobalLootModifier> codec() {
            return CODEC;
        }

        int conditionCount() {
            return conditions.length;
        }
    }

    private LegacyLootModifierCase() {}

    static boolean run() {
        return Drops.CODEC != null && new Drops(new LootItemCondition[0]).conditionCount() == 0;
    }
}
