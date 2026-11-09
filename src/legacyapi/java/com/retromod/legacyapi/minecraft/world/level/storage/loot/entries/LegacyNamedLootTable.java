/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.storage.loot.entries;

import com.mojang.serialization.MapCodec;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.SingleEntryContainerBase;
import net.minecraft.world.level.storage.loot.entries.UniformContainerBase;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/** A loot table named by key in code that had no registries to look it up in, found when the entry rolls. */
public final class LegacyNamedLootTable extends SingleEntryContainerBase {

    private final ResourceKey<LootTable> table;

    private LegacyNamedLootTable(ResourceKey<LootTable> table, int weight, int quality,
            Optional<Holder<LootItemCondition>> condition, Optional<Holder<LootItemFunction>> modifier) {
        super(weight, quality, condition, modifier);
        this.table = table;
    }

    /** The old {@code NestedLootTable.lootTableReference(key)}, for a call with no registries in reach. */
    public static UniformContainerBase.Builder<?> reference(ResourceKey<LootTable> table) {
        return simpleBuilder((weight, quality, condition, modifier) -> new LegacyNamedLootTable(table, weight, quality, condition, modifier));
    }

    /** The table rolls into {@code output}; the loot context's own registries find it, as they did before 26.3. */
    @Override
    protected void createItemStack(Consumer<ItemStack> output, LootContext context) {
        Optional<Holder.Reference<LootTable>> found = context.getResolver().get(table);
        // A missing table gives nothing, as an unknown table did before 26.3.
        found.ifPresent(holder -> holder.value().getRandomItemsRaw(context, output));
    }

    /** No codec writes this entry: it exists only in tables built in code while the game runs. */
    @Override
    public MapCodec<? extends SingleEntryContainerBase> codec() {
        throw new UnsupportedOperationException("The loot table reference to " + table + " was built by name in code"
                + " by a mod built before 26.3, so Retromod can roll it but cannot write it to data.");
    }
}
