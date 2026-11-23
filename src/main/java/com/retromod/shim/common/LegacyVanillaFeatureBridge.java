/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

import java.util.List;
import java.util.Map;

/** Moves old subclasses of vanilla features 26.3 made final records, and the configurations they read, onto rebuilt classes. */
public final class LegacyVanillaFeatureBridge {

    static final String FEATURES = "net/minecraft/world/level/levelgen/feature/";
    static final String CONFIGURATIONS = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/feature/configurations/";
    static final String TREE_BASE = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/feature/LegacyTreeFeature";

    // Vanilla features a subclass extends to override place itself; the plain legacy feature base is enough for them.
    static final List<String> PLACE_OVERRIDE_BASES = List.of(FEATURES + "LakeFeature", FEATURES + "CoralFeature",
            FEATURES + "CoralTreeFeature", FEATURES + "CoralClawFeature", FEATURES + "CoralMushroomFeature");

    // Deleted configuration classes and their rebuilt stand-ins, which keep the old fields and data keys.
    static final Map<String, String> CONFIGURATION_MOVES = Map.of(
            FEATURES + "configurations/TreeConfiguration", CONFIGURATIONS + "LegacyTreeConfiguration",
            FEATURES + "configurations/LargeDripstoneConfiguration", CONFIGURATIONS + "LegacyLargeDripstoneConfiguration",
            FEATURES + "LakeFeature$Configuration", CONFIGURATIONS + "LegacyLakeConfiguration");

    private LegacyVanillaFeatureBridge() {}

    public static void register(RetromodTransformer t) {
        // The rebuilt bases extend the feature base LegacyFeatureBridge generates, which it adds only on 26.3 hosts.
        if (!t.getSyntheticClasses().containsKey(LegacyFeatureBridge.LEGACY_FEATURE)) return;
        // TreeFeature's subclasses fill in doPlace; the rebuilt base runs it, the decorators, and the leaf distances.
        RebuiltApiClasses.registerTree(t, TREE_BASE);
        t.registerSuperclassRebase(FEATURES + "TreeFeature", TREE_BASE);
        for (String base : PLACE_OVERRIDE_BASES) t.registerSuperclassRebase(base, LegacyFeatureBridge.LEGACY_FEATURE);
        for (Map.Entry<String, String> move : CONFIGURATION_MOVES.entrySet()) {
            RebuiltApiClasses.registerTree(t, move.getValue());
            t.registerClassRedirect(move.getKey(), move.getValue());
        }
    }
}
