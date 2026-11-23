/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Keeps an old mod's own patch feature, built on the removed {@code RandomPatchConfiguration}, registering on 26.3. */
public final class LegacyRandomPatchBridge {

    static final String OLD = "net/minecraft/world/level/levelgen/feature/configurations/RandomPatchConfiguration";
    static final String FEATURE_CONFIGURATION = "net/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration";
    static final String REBUILT = LegacyRandomPatchApi.REBUILT;

    private LegacyRandomPatchBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read, LegacyFeatureBridge.hostHasFeatureInterface());
    }

    /**
     * Only where the host dropped both the class and its interface. 26.1 and 26.2 keep the interface,
     * and {@link LegacyRandomPatchApi} rebuilds the class there.
     */
    static void register(RetromodTransformer t, Function<String, ClassNode> host, boolean featureInterface) {
        if (host.apply(OLD) != null || host.apply(FEATURE_CONFIGURATION) != null || !featureInterface) return;
        // 26.3 removed FeatureConfiguration with the old feature model, which LegacyFeatureBridge rebuilds.
        RebuiltApiClasses.registerTree(t, REBUILT, new SimpleRemapper(FEATURE_CONFIGURATION, LegacyFeatureBridge.CONFIGURATION));
        t.registerClassRedirect(OLD, REBUILT);
    }
}
