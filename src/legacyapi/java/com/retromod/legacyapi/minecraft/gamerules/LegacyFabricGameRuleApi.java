/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.gamerules;

import java.util.function.BiConsumer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/** Fabric API's old {@code GameRuleRegistry} and {@code GameRuleFactory}, which 26.x Fabric API no longer ships. */
public final class LegacyFabricGameRuleApi {

    private LegacyFabricGameRuleApi() {}

    /** Old {@code GameRuleRegistry.register(name, category, type)}. */
    public static GameRule<?> register(String name, GameRuleCategory category, LegacyGameRuleType type) {
        return LegacyGameRules.register(name, category, type);
    }

    // The old GameRuleFactory forms. A missing minimum or maximum is the int range's end.

    public static LegacyGameRuleType createBooleanRule(boolean defaultValue) {
        return LegacyGameRuleType.bool(defaultValue);
    }

    public static LegacyGameRuleType createBooleanRule(boolean defaultValue, BiConsumer<?, ?> changed) {
        return LegacyGameRuleType.bool(defaultValue, changed);
    }

    public static LegacyGameRuleType createIntRule(int defaultValue) {
        return LegacyGameRuleType.integer(defaultValue);
    }

    public static LegacyGameRuleType createIntRule(int defaultValue, BiConsumer<?, ?> changed) {
        return LegacyGameRuleType.integer(defaultValue, changed);
    }

    public static LegacyGameRuleType createIntRule(int defaultValue, int minimum) {
        return LegacyGameRuleType.integer(defaultValue, minimum, Integer.MAX_VALUE, null);
    }

    public static LegacyGameRuleType createIntRule(int defaultValue, int minimum, BiConsumer<?, ?> changed) {
        return LegacyGameRuleType.integer(defaultValue, minimum, Integer.MAX_VALUE, changed);
    }

    public static LegacyGameRuleType createIntRule(int defaultValue, int minimum, int maximum) {
        return LegacyGameRuleType.integer(defaultValue, minimum, maximum, null);
    }

    public static LegacyGameRuleType createIntRule(int defaultValue, int minimum, int maximum, BiConsumer<?, ?> changed) {
        return LegacyGameRuleType.integer(defaultValue, minimum, maximum, changed);
    }
}
