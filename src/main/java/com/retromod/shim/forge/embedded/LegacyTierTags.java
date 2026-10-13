/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * The block tag a tool tier cannot mine, from the tier's old mining level: 0 wood, 1 stone,
 * 2 iron, 3 diamond, and 4 or more netherite. See {@link com.retromod.shim.forge.LegacyTierBridge}.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacyTierTags {
    private LegacyTierTags() {}

    private static final String[] BY_LEVEL = {"INCORRECT_FOR_WOODEN_TOOL", "INCORRECT_FOR_STONE_TOOL",
            "INCORRECT_FOR_IRON_TOOL", "INCORRECT_FOR_DIAMOND_TOOL", "INCORRECT_FOR_NETHERITE_TOOL"};

    public static Object incorrectFor(int level) {
        String field = BY_LEVEL[Math.max(0, Math.min(level, BY_LEVEL.length - 1))];
        try {
            return Class.forName("net.minecraft.tags.BlockTags", true, LegacyTierTags.class.getClassLoader())
                    .getField(field).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read BlockTags." + field, e);
        }
    }
}
