/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.gamerules;

import com.retromod.generated.LegacyGameRuleValue;
import java.util.function.BiConsumer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleType;
import net.minecraft.world.level.gamerules.GameRules;

/** The old {@code GameRules.Type}: the kind, default, bounds and change callback an old mod gave a rule it registers. */
public final class LegacyGameRuleType {

    final boolean bool;
    final Object defaultValue;
    final int min;
    final int max;
    final BiConsumer<Object, Object> callback;
    /** The host rule this type became, once registered; a host rule's own type has it from the start. */
    GameRule<?> rule;

    @SuppressWarnings("unchecked")
    private LegacyGameRuleType(boolean bool, Object defaultValue, int min, int max, BiConsumer<?, ?> callback) {
        this.bool = bool;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.callback = (BiConsumer<Object, Object>) callback;
    }

    /** The type an old visitor sees for a host rule: its kind and default, with no bounds or callback. */
    public static LegacyGameRuleType of(GameRule<?> rule) {
        boolean bool = rule.gameRuleType() == GameRuleType.BOOL;
        LegacyGameRuleType type = new LegacyGameRuleType(bool, rule.defaultValue(), Integer.MIN_VALUE, Integer.MAX_VALUE, null);
        type.rule = rule;
        return type;
    }

    /** Old {@code createRule()}: a value at the rule's default, held apart from every world's rules. */
    public LegacyGameRuleValue createRule() {
        if (rule == null) {
            throw new IllegalStateException("An old game rule type made a value before its rule was registered. "
                    + "Retromod backs old values with the host's rules, so the rule has to be registered first.");
        }
        return LegacyGameRuleValue.of(new GameRules(FeatureFlags.REGISTRY.allFlags()), rule);
    }

    /** Old {@code BooleanValue.create(default)}. */
    public static LegacyGameRuleType bool(boolean defaultValue) {
        return bool(defaultValue, null);
    }

    /** Old {@code BooleanValue.create(default, callback)}; the callback gets the server and the rule's value. */
    public static LegacyGameRuleType bool(boolean defaultValue, BiConsumer<?, ?> callback) {
        return new LegacyGameRuleType(true, defaultValue, 0, 0, callback);
    }

    /** Old {@code IntegerValue.create(default)}, which had no bounds. */
    public static LegacyGameRuleType integer(int defaultValue) {
        return integer(defaultValue, Integer.MIN_VALUE, Integer.MAX_VALUE, null);
    }

    /** Old {@code IntegerValue.create(default, callback)}. */
    public static LegacyGameRuleType integer(int defaultValue, BiConsumer<?, ?> callback) {
        return integer(defaultValue, Integer.MIN_VALUE, Integer.MAX_VALUE, callback);
    }

    /** An integer rule with bounds, as Fabric API's {@code GameRuleFactory.createIntRule} built. */
    public static LegacyGameRuleType integer(int defaultValue, int min, int max, BiConsumer<?, ?> callback) {
        if (min > max) {
            throw new IllegalArgumentException("An old game rule's minimum " + min + " is above its maximum " + max);
        }
        return new LegacyGameRuleType(false, defaultValue, min, max, callback);
    }
}
