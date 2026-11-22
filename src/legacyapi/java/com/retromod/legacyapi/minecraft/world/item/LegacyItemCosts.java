/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentExactPredicate;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;

/** An old mod's {@code ItemCost.CODEC}, which reads a trade cost before item components are bound with a stack that waits for them. */
public final class LegacyItemCosts {

    private static volatile Codec<ItemCost> codec;
    private static volatile Codec<Pair<Holder<Item>, Pair<Integer, DataComponentExactPredicate>>> fields;

    private LegacyItemCosts() {}

    /** Old {@code ItemCost.CODEC}: the host's codec once components are bound, and the same fields read early before. */
    public static Codec<ItemCost> codec() {
        if (codec == null) codec = Codec.of(ItemCost.CODEC, new CostDecoder());
        return codec;
    }

    // Every item's components are bound in one pass, so air answers for all of them.
    private static boolean componentsBound() {
        return Items.AIR.builtInRegistryHolder().areComponentsBound();
    }

    /** The id, count and components a cost is written with, read without building its stack. */
    private static Codec<Pair<Holder<Item>, Pair<Integer, DataComponentExactPredicate>>> fields() {
        if (fields == null) {
            fields = Codec.mapPair(Item.CODEC.fieldOf("id"), Codec.mapPair(
                    ExtraCodecs.POSITIVE_INT.fieldOf("count").orElse(1),
                    DataComponentExactPredicate.CODEC.optionalFieldOf("components", DataComponentExactPredicate.EMPTY))).codec();
        }
        return fields;
    }

    /** Reads with the host's codec once components are bound, and builds the cost on a waiting stack before. */
    private static final class CostDecoder implements Decoder<ItemCost> {
        @Override
        public <T> DataResult<Pair<ItemCost, T>> decode(DynamicOps<T> ops, T input) {
            if (componentsBound()) return ItemCost.CODEC.decode(ops, input);
            Pair<Pair<Holder<Item>, Pair<Integer, DataComponentExactPredicate>>, T> read;
            try {
                read = fields().decode(ops, input).result().orElse(null);
            } catch (RuntimeException malformed) {
                read = null;
            }
            // The host's codec reports anything these fields cannot read.
            if (read == null) return ItemCost.CODEC.decode(ops, input);
            Holder<Item> item = read.getFirst().getFirst();
            int count = read.getFirst().getSecond().getFirst();
            DataComponentExactPredicate components = read.getFirst().getSecond().getSecond();
            try {
                ItemStack stack = EarlyItemStacks.of(item, count, components.asPatch());
                return DataResult.success(Pair.of(new ItemCost(item, count, components, stack), read.getSecond()));
            } catch (RuntimeException failure) {
                return DataResult.error(() -> "Could not read trade cost " + input + " before item components were bound: " + failure);
            }
        }
    }
}
