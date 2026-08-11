/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2;
import com.retromod.shim.fabric.Fabric_1_21_2_to_1_21_3;
import com.retromod.shim.fabric.Fabric_1_21_3_to_1_21_4;
import com.retromod.shim.fabric.Fabric_1_21_4_to_1_21_5;
import com.retromod.shim.fabric.Fabric_1_21_5_to_1_21_6;
import com.retromod.shim.fabric.Fabric_1_21_6_to_1_21_7;
import com.retromod.shim.fabric.Fabric_1_21_7_to_1_21_8;
import com.retromod.shim.fabric.Fabric_1_21_8_to_1_21_9;
import com.retromod.shim.fabric.Fabric_1_21_9_to_1_21_10;
import com.retromod.shim.fabric.Fabric_1_21_to_1_21_1;
import com.retromod.shim.fabric.Fabric_26_1_to_26_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShimRegistryZeroPatchAliasTest {

    private static final String WINDOW = "com/mojang/blaze3d/platform/Window";

    @AfterEach
    void resetTransformer() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void fabricMetadataVersion1_21_0EntersThe1_21ShimChain() {
        ShimRegistry registry = new ShimRegistry();
        registry.register(new Fabric_1_21_to_1_21_1());

        List<VersionShim> chain = registry.findShimChain("fabric", "1.21.0", "1.21.1");

        assertEquals(1, chain.size());
        assertEquals("1.21", chain.get(0).getSourceVersion());
        assertEquals("1.21.1", chain.get(0).getTargetVersion());
    }

    @Test
    void zeroPatchAliasesCoverEveryShimmedModernReleaseLine() {
        assertEquals("1.13.2", ShimRegistry.resolveVersion("1.13.0"));
        assertEquals("1.14.4", ShimRegistry.resolveVersion("1.14.0"));
        assertEquals("1.15.2", ShimRegistry.resolveVersion("1.15.0"));
        assertEquals("1.16.5", ShimRegistry.resolveVersion("1.16.0"));
        assertEquals("1.17", ShimRegistry.resolveVersion("1.17.0"));
        assertEquals("1.18", ShimRegistry.resolveVersion("1.18.0"));
        assertEquals("1.19", ShimRegistry.resolveVersion("1.19.0"));
        assertEquals("1.20", ShimRegistry.resolveVersion("1.20.0"));
        assertEquals("1.21", ShimRegistry.resolveVersion("1.21.0"));
    }

    @Test
    void dynamicFpsVersionReachesTheWindowHandleRedirectOn26_2() {
        ShimRegistry registry = fullFabric1_21Chain();
        List<VersionShim> chain = registry.findShimChain("fabric", "1.21.0", "26.2");

        assertEquals(13, chain.size());
        assertEquals(Fabric_1_21_11_to_26_1.class, chain.get(11).getClass());

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        chain.forEach(shim -> shim.registerRedirects(transformer));

        var redirect = transformer.getMethodRedirects().get(
                new RetromodTransformer.MethodKey(WINDOW, "getWindow", "()J"));
        assertEquals(WINDOW, redirect.owner());
        assertEquals("handle", redirect.name());
        assertEquals("()J", redirect.desc());
    }

    private static ShimRegistry fullFabric1_21Chain() {
        ShimRegistry registry = new ShimRegistry();
        registry.register(new Fabric_1_21_to_1_21_1());
        registry.register(new Fabric_1_21_1_to_1_21_2());
        registry.register(new Fabric_1_21_2_to_1_21_3());
        registry.register(new Fabric_1_21_3_to_1_21_4());
        registry.register(new Fabric_1_21_4_to_1_21_5());
        registry.register(new Fabric_1_21_5_to_1_21_6());
        registry.register(new Fabric_1_21_6_to_1_21_7());
        registry.register(new Fabric_1_21_7_to_1_21_8());
        registry.register(new Fabric_1_21_8_to_1_21_9());
        registry.register(new Fabric_1_21_9_to_1_21_10());
        registry.register(new Fabric_1_21_10_to_1_21_11());
        registry.register(new Fabric_1_21_11_to_26_1());
        registry.register(new Fabric_26_1_to_26_2());
        return registry;
    }
}
