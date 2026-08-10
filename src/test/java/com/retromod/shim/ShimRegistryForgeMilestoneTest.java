/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

class ShimRegistryForgeMilestoneTest {
    @Test
    void forge120PatchHostUsesThe120Milestone() {
        ShimRegistry registry = new ShimRegistry();
        registry.register(shim("forge", "1.16.5", "1.17"));
        registry.register(shim("forge", "1.17", "1.20"));

        assertEquals(2, registry.findShimChain("forge", "1.16.4", "1.20.1").size());
    }

    @Test
    void fabricPatchHostStillRequiresItsRealPatchShim() {
        ShimRegistry registry = new ShimRegistry();
        registry.register(shim("fabric", "1.16.5", "1.20"));

        assertTrue(registry.findShimChain("fabric", "1.16.5", "1.20.1").isEmpty());
    }

    private static VersionShim shim(String loader, String source, String target) {
        return new VersionShim() {
            @Override public String getShimName() { return source + " to " + target; }
            @Override public String getSourceVersion() { return source; }
            @Override public String getTargetVersion() { return target; }
            @Override public String getModLoaderType() { return loader; }
            @Override public void registerRedirects(RetromodTransformer transformer) { }
        };
    }
}
