/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_to_1_21_1;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A mod that declares a pre-release floor, as Chunky's {@code >=1.21-rc.1} does, starts from that release's chain. */
class ShimRegistryPrereleaseSourceTest {

    @Test
    @DisplayName("1.21-rc.1, 1.21-beta.2 and 1.20.2-beta.2 resolve to their releases")
    void prereleasesResolveToTheirRelease() {
        assertEquals("1.21", ShimRegistry.resolveVersion("1.21-rc.1"));
        assertEquals("1.21", ShimRegistry.resolveVersion("1.21-beta.2"));
        assertEquals("1.20.2", ShimRegistry.resolveVersion("1.20.2-beta.2"));
        assertEquals("1.21.11", ShimRegistry.resolveVersion("1.21.11-pre-3"));
    }

    @Test
    @DisplayName("a 1.21-rc.1 Fabric mod takes the 1.21 chain instead of none")
    void prereleaseSourceFindsAChain() {
        ShimRegistry registry = new ShimRegistry();
        registry.register(new Fabric_1_21_to_1_21_1());

        List<VersionShim> chain = registry.findShimChain("fabric", "1.21-rc.1", "1.21.1");

        assertEquals(1, chain.size(), "the pre-release suffix must not hide the source version from the chain");
    }

    @Test
    @DisplayName("names that only look alike are left alone")
    void otherNamesPassThrough() {
        assertEquals("1.21-custom", ShimRegistry.resolveVersion("1.21-custom"));
        assertEquals("26.3", ShimRegistry.resolveVersion("26.3-rc.2"));
    }
}
