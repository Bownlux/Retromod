/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common.loot;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/** NeoForge 1.21.1's {@code AddTableLootModifier}, built from a condition array, over 26.3's loot modifier base. */
public class LegacyAddTableLootModifier extends LegacyLootModifier {

    private final ResourceKey<LootTable> table;

    public LegacyAddTableLootModifier(LootItemCondition[] conditions, ResourceKey<LootTable> table) {
        super(conditions);
        this.table = table;
    }

    public ResourceKey<LootTable> table() {
        return table;
    }

    /** Rolls the extra table with the same context and adds what it drops; a table the world does not have adds nothing. */
    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        // The host's splitter cuts oversized rolls into full stacks and drops items the world's features disable.
        context.getResolver().get(table).ifPresent(extra -> extra.value().getRandomItemsRaw(context,
                LootTable.createStackSplitter(context.getLevel(), generatedLoot::add)));
        return generatedLoot;
    }
}
