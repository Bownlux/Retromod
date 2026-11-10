/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Forge runtime never calls registerAll, so a Forge 1.20.1 mod translated in game on Forge 26.2
 * failed with NoClassDefFoundError for IConditionSerializer while the CLI's output linked.
 */
class ForgeRuntimeConditionStandInTest {

    private static final String SERIALIZER = ForgeRecipeConditionSynthetics.SERIALIZER;
    private static final String CRAFTING_HELPER = ForgeRecipeConditionSynthetics.CRAFTING_HELPER;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"1.21.11", "26.2", "26.3"})
    @DisplayName("The 1.21.11 shim gives a Forge target the condition stand-ins its host lacks")
    void shimRegistersTheStandIns(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        new Forge_1_21_10_to_1_21_11().registerRedirects(transformer);

        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        assertTrue(synthetics.containsKey(SERIALIZER), "IConditionSerializer is registered for " + target);
        assertTrue(synthetics.containsKey(CRAFTING_HELPER), "CraftingHelper is registered for " + target);
    }

    @Test
    @DisplayName("A NeoForge target keeps its own condition handling")
    void neoForgeTargetIsUntouched() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        new Forge_1_21_10_to_1_21_11().registerRedirects(transformer);

        assertFalse(transformer.getSyntheticClasses().containsKey(SERIALIZER));
    }

    @Test
    @DisplayName("The CLI's registerAll and the shim register the same stand-in")
    void cliAndRuntimeAgree() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        new Forge_1_21_10_to_1_21_11().registerRedirects(transformer);
        byte[] runtime = transformer.getSyntheticClasses().get(SERIALIZER);
        transformer.clearRedirectsForTesting();
        ForgeNeoForgeSynthetics.registerAll(transformer);

        assertArrayEquals(runtime, transformer.getSyntheticClasses().get(SERIALIZER));
    }
}
