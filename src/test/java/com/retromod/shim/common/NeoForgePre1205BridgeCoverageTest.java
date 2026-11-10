/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.FieldKey;
import com.retromod.core.RetromodTransformer.FieldTarget;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.neoforge.NeoForge_1_20_2_to_1_20_3;
import com.retromod.shim.neoforge.NeoForge_1_20_4_to_1_20_5;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/** A NeoForge 1.20.2 to 1.20.4 mod gets the 1.20.3 and 1.20.5 vanilla bridges from NeoForge's own chain. */
class NeoForgePre1205BridgeCoverageTest {

    private static final String MOB_TYPE = "net/minecraft/world/entity/MobType";
    private static final String TREE_GROWER = "net/minecraft/world/level/block/grower/AbstractTreeGrower";
    private static final FieldKey ADDITION =
            new FieldKey("net/minecraft/world/entity/ai/attributes/AttributeModifier$Operation", "ADDITION");

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("NeoForge 1.20.6 gets every bridge from the 1.20.3 and 1.20.5 NeoForge shims, without the Forge shim")
    void neoForge1206GetsTheBridgesFromItsOwnShims() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.6";
        new NeoForge_1_20_2_to_1_20_3().registerRedirects(transformer);
        new NeoForge_1_20_4_to_1_20_5().registerRedirects(transformer);

        assertAllBridges("1.20.6");
    }

    @Test
    @DisplayName("NeoForge 1.20.4 bridges the tree grower but keeps MobType and the old attribute operations")
    void neoForge1204BridgesOnlyTheTreeGrower() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        new NeoForge_1_20_2_to_1_20_3().registerRedirects(transformer);

        Map<String, String> classes = transformer.getClassRedirects();
        assertEquals(LegacyTreeGrowerBridge.GROWER, classes.get(TREE_GROWER), "1.20.3 removed AbstractTreeGrower");

        // The 1.20.5 shim never runs on this host, and its bridges also check the host themselves.
        new NeoForge_1_20_4_to_1_20_5().registerRedirects(transformer);
        classes = transformer.getClassRedirects();
        assertFalse(classes.containsKey(MOB_TYPE), "1.20.4 still has MobType");
        assertFalse(classes.containsKey(LegacySpawnPlacementTypeBridge.OLD_TYPE), "1.20.4 still has SpawnPlacements.Type");
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacySynchedDataBridge.HELPER),
                "1.20.4 still has SynchedEntityData.define");
        assertFalse(transformer.getFieldRedirects().containsKey(ADDITION), "1.20.4 still has Operation.ADDITION");
    }

    @Test
    @DisplayName("NeoForge 26.3: the NeoForge chain from 1.20.2 registers every bridge on its own")
    void neoForgeChainToTheNewestHostRegistersEveryBridge() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ShimRegistry registry = new ShimRegistry();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) registry.register(shim);
        List<VersionShim> chain = registry.findShimChain("neoforge", "1.20.2", "26.3");

        assertFalse(chain.isEmpty(), "no NeoForge chain from 1.20.2 to 26.3");
        for (VersionShim shim : chain) {
            assertEquals("neoforge", shim.getModLoaderType(), shim.getShimName() + " is not a NeoForge shim");
            shim.registerRedirects(transformer);
        }
        assertAllBridges("26.3");
    }

    private void assertAllBridges(String host) {
        Map<String, String> classes = transformer.getClassRedirects();
        assertEquals(LegacyTreeGrowerBridge.GROWER, classes.get(TREE_GROWER),
                "AbstractTreeGrower is not bridged on " + host);
        assertEquals(LegacyMobTypeBridge.BRIDGE, classes.get(MOB_TYPE), "MobType is not bridged on " + host);
        assertEquals(LegacySpawnPlacementTypeBridge.NEW_TYPE, classes.get(LegacySpawnPlacementTypeBridge.OLD_TYPE),
                "SpawnPlacements.Type is not bridged on " + host);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacySynchedDataBridge.HELPER),
                "the entity data helper is not registered on " + host);
        FieldTarget addition = transformer.getFieldRedirects().get(ADDITION);
        assertNotNull(addition, "Operation.ADDITION is not renamed on " + host);
        assertEquals("ADD_VALUE", addition.name());
    }
}
