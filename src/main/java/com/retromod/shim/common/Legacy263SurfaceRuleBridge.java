/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Rebuilds the old surface rule API on 26.3's material rules, for every loader. */
public final class Legacy263SurfaceRuleBridge {

    static final String SURFACE_RULES = "net/minecraft/world/level/levelgen/SurfaceRules";
    static final String MATERIAL = "net/minecraft/world/level/levelgen/material/";
    static final String MATERIAL_RULES = MATERIAL + "MaterialRules";
    static final String RULE = MATERIAL + "rule/MaterialRule";
    static final String RULE_EVALUATOR = MATERIAL + "rule/RuleEvaluator";
    static final String CONDITION = MATERIAL + "condition/MaterialCondition";
    static final String CONDITION_EVALUATOR = MATERIAL + "condition/ConditionEvaluator";
    static final String CONTEXT = MATERIAL + "MaterialRuleContext";
    static final String SETTINGS = "net/minecraft/world/level/levelgen/NoiseGeneratorSettings";
    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/material/LegacyMaterialRules";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";

    static final String[] CONSTANTS = {"ON_FLOOR", "UNDER_FLOOR", "DEEP_UNDER_FLOOR", "VERY_DEEP_UNDER_FLOOR",
            "ON_CEILING", "UNDER_CEILING"};
    // Old context fields a mod's access transformer opened, and the 26.3 accessor that reads each.
    static final String[][] CONTEXT_FIELDS = {
            {"blockX", "I", "blockX"}, {"blockY", "I", "blockY"}, {"blockZ", "I", "blockZ"},
            {"surfaceDepth", "I", "surfaceDepth"}, {"waterHeight", "I", "waterHeight"},
            {"stoneDepthBelow", "I", "stoneDepthBelow"}, {"stoneDepthAbove", "I", "stoneDepthAbove"},
            {"minSurfaceLevel", "I", "getMinSurfaceLevel"}, {"surfaceSecondary", "D", "getSurfaceSecondary"}};

    private Legacy263SurfaceRuleBridge() {}

    /** Registers the rebuilt API when the host has material rules and no surface rules. */
    public static void register(RetromodTransformer t) {
        if (!hostHasMaterialRules(ClassResourceInspector.read(SURFACE_RULES), ClassResourceInspector.read(MATERIAL_RULES),
                ClassResourceInspector.read(BUILT_IN_REGISTRIES) != null)) {
            return;
        }
        RebuiltApiClasses.registerTree(t, HELPER);
        registerTypes(t);
        registerFactories(t);
        registerCalls(t);
        registerContextFields(t);
    }

    /** Whether 26.3's material rules replaced surface rules; offline, where no game class is readable, the version decides. */
    static boolean hostHasMaterialRules(Object surfaceRules, Object materialRules, boolean gameReadable) {
        if (materialRules != null) return surfaceRules == null;
        if (gameReadable) return false;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
    }

    /** The old evaluated rule, condition and context are the host's compiled ones under new names. */
    static void registerTypes(RetromodTransformer t) {
        t.registerClassRedirect(SURFACE_RULES + "$SurfaceRule", RULE_EVALUATOR);
        t.registerClassRedirect(SURFACE_RULES + "$Condition", CONDITION_EVALUATOR);
        t.registerClassRedirect(SURFACE_RULES + "$Context", CONTEXT);
        t.registerClassRedirect(SURFACE_RULES + "$LazyXZCondition", CONTEXT + "$LazyXZCondition");
        t.registerClassRedirect(SURFACE_RULES + "$LazyYCondition", CONTEXT + "$LazyYCondition");
    }

    /** The factories 26.3 renamed or dropped, and the old constants it turned into registry keys. */
    static void registerFactories(RetromodTransformer t) {
        String condition = "L" + CONDITION + ";";
        // isBiome now needs the biome lookup; the rebuilt condition matches keys where it is tested.
        t.registerMethodRedirect(MATERIAL_RULES, "isBiome", "([Lnet/minecraft/resources/ResourceKey;)" + condition,
                HELPER, "isBiome", "([Lnet/minecraft/resources/ResourceKey;)" + condition);
        // The old noise condition sampled at block height zero, which 26.3 calls a 2D noise condition.
        for (String desc : new String[] {"(Lnet/minecraft/resources/ResourceKey;D)", "(Lnet/minecraft/resources/ResourceKey;DD)"}) {
            t.registerMethodRedirect(MATERIAL_RULES, "noiseCondition", desc + condition,
                    MATERIAL_RULES, "noiseCondition2d", desc + condition);
        }
        for (String constant : CONSTANTS) {
            t.registerFieldRedirect(MATERIAL_RULES, constant, HELPER, constant);
            t.registerFieldRedirect(SURFACE_RULES, constant, HELPER, constant);
        }
    }

    /** Old code that applies a rule or condition to a context compiles it against the context instead. */
    static void registerCalls(RetromodTransformer t) {
        String object = "(Ljava/lang/Object;)Ljava/lang/Object;";
        String context = "L" + CONTEXT + ";";
        t.registerMethodRedirect(RULE, "apply", object, HELPER, "applyRule",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        t.registerMethodRedirect(RULE, "apply", "(" + context + ")L" + RULE_EVALUATOR + ";", HELPER, "applyRule",
                "(Ljava/lang/Object;" + context + ")L" + RULE_EVALUATOR + ";", true);
        t.registerMethodRedirect(CONDITION, "apply", object, HELPER, "applyCondition",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        t.registerMethodRedirect(CONDITION, "apply", "(" + context + ")L" + CONDITION_EVALUATOR + ";", HELPER,
                "applyCondition", "(Ljava/lang/Object;" + context + ")L" + CONDITION_EVALUATOR + ";", true);
        // The settings now hold their rule in a holder.
        t.registerMethodRedirect(SETTINGS, "surfaceRule", "()L" + RULE + ";", HELPER, "surfaceRule",
                "(Ljava/lang/Object;)L" + RULE + ";", true);
    }

    /** The old context's fields, read through the accessors 26.3's context offers. */
    static void registerContextFields(RetromodTransformer t) {
        // Under both names, since a read can meet this before or after the class redirect.
        for (String owner : new String[] {SURFACE_RULES + "$Context", CONTEXT}) {
            t.registerExactFieldRedirect(owner, "biome", "Ljava/util/function/Supplier;",
                    HELPER, "biome", "(Ljava/lang/Object;)Ljava/util/function/Supplier;");
            // Read-only: the host moves its context itself, and an old write has no safe meaning.
            for (String[] field : CONTEXT_FIELDS) {
                t.registerFieldAccessorRedirect(owner, field[0], field[2], "()" + field[1], null, null);
            }
        }
    }
}
