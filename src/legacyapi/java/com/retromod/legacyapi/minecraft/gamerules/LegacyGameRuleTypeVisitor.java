/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.gamerules;

import net.minecraft.world.level.gamerules.GameRule;

/** The old {@code GameRules.GameRuleTypeVisitor}: an old mod's visitor keeps overriding these. */
public interface LegacyGameRuleTypeVisitor {

    /** Called first for every rule. */
    default void visit(GameRule<?> rule, LegacyGameRuleType type) {
    }

    /** Called after {@link #visit} for a true or false rule. */
    default void visitBoolean(GameRule<?> rule, LegacyGameRuleType type) {
    }

    /** Called after {@link #visit} for a whole number rule. */
    default void visitInteger(GameRule<?> rule, LegacyGameRuleType type) {
    }
}
