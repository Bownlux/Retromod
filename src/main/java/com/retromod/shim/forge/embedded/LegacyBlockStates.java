/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.util.function.Supplier;

/**
 * Reads the lazy base state an old stair block named, for a parent that now takes the state
 * itself. The supplier may name one of the mod's own blocks that is not registered yet; the stair
 * then takes stone's state, which only seeds sounds and particles it already overrides.
 */
public final class LegacyBlockStates {
    private LegacyBlockStates() {}

    public static Object resolve(Supplier<?> state) {
        try {
            Object value = state.get();
            if (value != null) return value;
        } catch (RuntimeException | LinkageError notYet) {
            // the base block registers later in the same event
        }
        try {
            Object stone = Class.forName("net.minecraft.world.level.block.Blocks", true,
                    LegacyBlockStates.class.getClassLoader()).getField("STONE").get(null);
            return stone.getClass().getMethod("defaultBlockState").invoke(stone);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not find a base state for an old stair block", e);
        }
    }
}
