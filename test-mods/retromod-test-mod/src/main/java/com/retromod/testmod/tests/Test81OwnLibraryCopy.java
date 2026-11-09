/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import appeng.tile.AEBaseTileEntity;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;

/** A class this mod ships under a library name Retromod moves stays the mod's own, as GeckoLib 4's must. */
public final class Test81OwnLibraryCopy implements Test {

    @Override
    public String description() {
        return "A class this mod ships under a library name Retromod moves stays its own";
    }

    @Override
    public TestResult run() {
        String origin = AEBaseTileEntity.origin();
        String name = AEBaseTileEntity.class.getName();
        if (!"retromod-test-mod".equals(origin)) return TestResult.fail("origin() answered " + origin);
        if (!name.equals("appeng.tile.AEBaseTileEntity")) return TestResult.fail("the class is named " + name);
        return TestResult.success();
    }
}
