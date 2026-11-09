/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.util.random;

import net.minecraft.util.random.Weighted;

/** The {@code WeightedEntry} 1.21.5 removed; its {@code Wrapper} is the host's {@code Weighted} now. */
public interface WeightedEntry {

    Weight getWeight();

    /** A value with a weight, as the old {@code wrap} built it, so host weighted lists accept it too. */
    static Weighted<Object> wrap(Object data, int weight) {
        return new Weighted<>(data, Weight.of(weight).asInt());
    }

    /** The old base for entries that keep their weight in a field. */
    abstract class IntrusiveBase implements WeightedEntry {

        private final Weight weight;

        public IntrusiveBase(int weight) {
            this.weight = Weight.of(weight);
        }

        public IntrusiveBase(Weight weight) {
            this.weight = weight;
        }

        @Override
        public Weight getWeight() {
            return weight;
        }
    }
}
