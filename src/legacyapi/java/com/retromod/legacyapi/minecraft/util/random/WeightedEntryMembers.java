/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.util.random;

import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;

import java.util.List;
import java.util.Optional;

/** The {@code WeightedRandom} and {@code WeightedEntry.Wrapper} members 1.21.5 removed or reshaped. */
public final class WeightedEntryMembers {

    private WeightedEntryMembers() {}

    /** {@code getTotalWeight(entries)}: the summed weights, refusing a sum past the largest int. */
    public static int getTotalWeight(List<?> entries) {
        long total = 0;
        for (Object entry : entries) {
            total += weight(entry);
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The weights of " + entries.size() + " entries add up to " + total
                    + ", more than a weighted pick can hold");
        }
        return (int) total;
    }

    /** {@code getRandomItem(random, entries)}: one entry picked in proportion to its weight. */
    public static Optional<Object> getRandomItem(RandomSource random, List<?> entries) {
        return getRandomItem(random, entries, getTotalWeight(entries));
    }

    /** {@code getRandomItem(random, entries, total)}, with the total already summed by the caller. */
    public static Optional<Object> getRandomItem(RandomSource random, List<?> entries, int totalWeight) {
        if (totalWeight < 0) {
            throw new IllegalArgumentException("A weighted pick was given a negative total weight: " + totalWeight);
        }
        if (totalWeight == 0) return Optional.empty();
        return getWeightedItem(entries, random.nextInt(totalWeight));
    }

    /** {@code getWeightedItem(entries, index)}: the entry whose weight range holds the index. */
    public static Optional<Object> getWeightedItem(List<?> entries, int weightedIndex) {
        int remaining = weightedIndex;
        for (Object entry : entries) {
            remaining -= weight(entry);
            if (remaining < 0) return Optional.of(entry);
        }
        return Optional.empty();
    }

    /** {@code Wrapper.getData()}, which the host's {@code Weighted} calls {@code value()}. */
    public static Object getData(Weighted<?> wrapper) {
        return wrapper.value();
    }

    /** {@code Wrapper.getWeight()}, which the host keeps as a plain int. */
    public static Weight wrapperWeight(Weighted<?> wrapper) {
        return Weight.of(wrapper.weight());
    }

    /** {@code WeightedEntry.getWeight()} on an entry that may be a host {@code Weighted} now. */
    public static Weight weightOf(Object entry) {
        if (entry instanceof WeightedEntry legacy) return legacy.getWeight();
        return Weight.of(((Weighted<?>) entry).weight());
    }

    /** {@code new Wrapper(data, weight)}, built as the host's {@code Weighted}. */
    public static Weighted<Object> newWrapper(Object data, Weight weight) {
        return new Weighted<>(data, weight.asInt());
    }

    private static int weight(Object entry) {
        // An old mod's own entry implements the rebuilt interface; a wrapped value is a host Weighted.
        if (entry instanceof WeightedEntry legacy) return legacy.getWeight().asInt();
        return ((Weighted<?>) entry).weight();
    }
}
