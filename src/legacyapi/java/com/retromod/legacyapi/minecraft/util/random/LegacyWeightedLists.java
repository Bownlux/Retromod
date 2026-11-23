/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.util.random;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.random.WeightedRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The members 1.21.4's two weighted lists had that 1.21.5's WeightedList answers differently. */
public final class LegacyWeightedLists {

    private LegacyWeightedLists() {}

    /** {@code SimpleWeightedRandomList.getRandom(random)}: the drawn value with its weight, as the old wrapper. */
    public static Optional<Weighted<Object>> wrappedRandom(WeightedList<Object> list, RandomSource random) {
        // The rebuilt wrapper is the host's Weighted, so the pick runs over the list's own weighted values.
        return WeightedRandom.getRandomItem(random, list.unwrap(), Weighted::weight);
    }

    // A WeightedRandomList held entries that carry their own weight and handed the entries themselves out.

    /** {@code WeightedRandomList.create(entries)}: each entry weighted by its own weight. */
    public static WeightedList<Object> fromEntries(List<?> entries) {
        WeightedList.Builder<Object> builder = WeightedList.builder();
        for (Object entry : entries) builder.add(entry, WeightedEntryMembers.weightOf(entry).asInt());
        return builder.build();
    }

    /** {@code WeightedRandomList.create(entries...)}. */
    public static WeightedList<Object> fromEntries(Object[] entries) {
        return fromEntries(List.of(entries));
    }

    /** {@code WeightedRandomList.create()}: no entries. */
    public static WeightedList<Object> noEntries() {
        return fromEntries(List.of());
    }

    /** {@code WeightedRandomList.unwrap()}: the entries themselves, not the host's weighted pairs. */
    public static List<Object> entries(WeightedList<Object> list) {
        List<Object> entries = new ArrayList<>();
        for (Weighted<Object> weighted : list.unwrap()) entries.add(weighted.value());
        return entries;
    }

    /**
     * {@code WeightedRandomList.codec(entryCodec)}: a list of entries, each carrying its weight. An entry type that
     * lost its own weight on the host, such as a mob spawn entry, reads the weight from beside it in the same
     * object, as the host's own spawn lists do; anything that is not an object reads as the plain old list.
     */
    public static Codec<WeightedList<Object>> entryCodec(Codec<Object> entry) {
        Codec<WeightedList<Object>> besideEntry = Codec.pair(entry, Codec.INT.optionalFieldOf("weight", 1).codec())
                .listOf().xmap(LegacyWeightedLists::fromWeightedPairs, LegacyWeightedLists::weightedPairs);
        Codec<WeightedList<Object>> plain = entry.listOf().xmap(LegacyWeightedLists::fromEntries, LegacyWeightedLists::entries);
        return Codec.withAlternative(besideEntry, plain);
    }

    /** Each entry with its own weight where it has one, else the weight read beside it. */
    private static WeightedList<Object> fromWeightedPairs(List<Pair<Object, Integer>> pairs) {
        WeightedList.Builder<Object> builder = WeightedList.builder();
        for (Pair<Object, Integer> pair : pairs) {
            Object entry = pair.getFirst();
            boolean ownWeight = entry instanceof WeightedEntry || entry instanceof Weighted<?>;
            builder.add(entry, ownWeight ? WeightedEntryMembers.weightOf(entry).asInt() : pair.getSecond());
        }
        return builder.build();
    }

    private static List<Pair<Object, Integer>> weightedPairs(WeightedList<Object> list) {
        List<Pair<Object, Integer>> pairs = new ArrayList<>();
        for (Weighted<Object> weighted : list.unwrap()) pairs.add(Pair.of(weighted.value(), weighted.weight()));
        return pairs;
    }
}
