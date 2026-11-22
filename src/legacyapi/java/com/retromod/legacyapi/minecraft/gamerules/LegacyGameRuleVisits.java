/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.gamerules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

/** The old static game rule calls that listed every rule, answered from the host's game rule registry. */
public final class LegacyGameRuleVisits {

    private LegacyGameRuleVisits() {}

    /** Old {@code GameRules.visitGameRuleTypes(visitor)}: each registered rule, vanilla and mod, in registry order. */
    public static void visitGameRuleTypes(LegacyGameRuleTypeVisitor visitor) {
        for (Map.Entry<GameRule<?>, LegacyGameRuleType> entry : gameRuleTypes().entrySet()) {
            GameRule<?> rule = entry.getKey();
            LegacyGameRuleType type = entry.getValue();
            visitor.visit(rule, type);
            if (type.bool) {
                visitor.visitBoolean(rule, type);
            } else {
                visitor.visitInteger(rule, type);
            }
        }
    }

    /** Old {@code GameRules.GAME_RULE_TYPES}: a read-only map of every registered rule to its old-shaped type, in id order. */
    public static Map<GameRule<?>, LegacyGameRuleType> gameRuleTypes() {
        // The old map was sorted by rule id, so visitors and config screens listed rules alphabetically.
        List<GameRule<?>> rules = new ArrayList<>();
        BuiltInRegistries.GAME_RULE.forEach(rules::add);
        rules.sort(Comparator.comparing(GameRule::id));
        Map<GameRule<?>, LegacyGameRuleType> types = new LinkedHashMap<>();
        for (GameRule<?> rule : rules) {
            types.put(rule, LegacyGameRules.typeOf(rule));
        }
        return Collections.unmodifiableMap(types);
    }

    /** Old {@code new GameRules()}, which held every rule: the host's rules with every feature flag on. */
    public static GameRules newGameRules() {
        return new GameRules(FeatureFlags.REGISTRY.allFlags());
    }
}
