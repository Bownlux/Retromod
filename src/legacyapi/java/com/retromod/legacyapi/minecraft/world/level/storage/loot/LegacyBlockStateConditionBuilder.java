/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.storage.loot;

import net.minecraft.advancements.predicates.StatePropertiesPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.MatchBlock;

/** The old block state loot condition builder, which builds 26.3's block-matching condition. */
public class LegacyBlockStateConditionBuilder implements LootItemCondition.Builder {

    private final Block block;
    private StatePropertiesPredicate.Builder properties;

    public LegacyBlockStateConditionBuilder(Block block) {
        this.block = block;
    }

    /** {@code LootItemBlockStatePropertyCondition.hasBlockStateProperties(block)}. */
    public static LegacyBlockStateConditionBuilder hasBlockStateProperties(Block block) {
        return new LegacyBlockStateConditionBuilder(block);
    }

    /** The state properties the block must have. */
    public LegacyBlockStateConditionBuilder setProperties(StatePropertiesPredicate.Builder properties) {
        this.properties = properties;
        return this;
    }

    @Override
    public LootItemCondition build() {
        LootItemCondition.Builder match = properties == null ? MatchBlock.blockMatches(BuiltInRegistries.BLOCK, block)
                : MatchBlock.blockMatches(BuiltInRegistries.BLOCK, block, properties);
        return match.build();
    }
}
