/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.util.McReflect;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Offline NeoForge targets get a stand-in only for a class the target lacks, as the game does. */
class OfflineNeoForgeStandInTest {

    private static final String SPAWN_EGG = "net/neoforged/neoforge/common/DeferredSpawnEggItem";
    private static final String LOADING_CONTEXT = "net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext";
    private static final String DIST_EXECUTOR = "net/minecraftforge/fml/DistExecutor";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedHost;

    @BeforeEach
    void saveHost() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
        McReflect.setForceNeoForge(false);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @CsvSource({"1.21.1, false, true", "1.21.5, false, true", "1.21.11, true, true", "26.1, true, true",
            "26.3, true, true", "1.20.6, false, false"})
    void neoForgeRemovalsComeOnlyFromTheVersionThatLacksThem(String target, boolean removed, boolean noContext) {
        RetromodVersion.TARGET_MC_VERSION = target;
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
        ModVersionInfo forgeMod = new ModVersionInfo("fixture", "1.0", "1.20.1", "forge", "47",
                Set.of(), Set.of(), false);

        OfflineRegistration.register(transformer, new ShimRegistry(), forgeMod, target, List.of());
        var synthetics = transformer.getSyntheticClasses();

        // NeoForge 21.1 ships DeferredSpawnEggItem; the stand-in calls SpawnEggItem(Properties) from 1.21.9.
        assertEquals(removed, synthetics.containsKey(SPAWN_EGG), "DeferredSpawnEggItem stand-in");
        // FMLJavaModLoadingContext is already gone from NeoForge 21.1.252.
        assertEquals(noContext, synthetics.containsKey(LOADING_CONTEXT), "FMLJavaModLoadingContext stand-in");
        // NeoForge never ships Forge's own classes, so their stand-ins come on every target.
        assertEquals(true, synthetics.containsKey(DIST_EXECUTOR), "DistExecutor stand-in");
    }
}
