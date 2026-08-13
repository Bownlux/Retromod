/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.loader.api.FabricLoader;

/** Confirms issue #186 does not install a translated duplicate of Fabric API. */
public final class Test16SingleFabricApiProvider implements Test {

    @Override
    public String description() {
        return "single Fabric API provider";
    }

    @Override
    public TestResult run() {
        long providers = FabricLoader.getInstance().getAllMods().stream()
                .filter(container -> "fabric-api".equals(container.getMetadata().getId()))
                .count();
        return providers == 1
                ? TestResult.success()
                : TestResult.fail("expected one Fabric API provider, found " + providers);
    }
}
