/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;

/** Load-time regression for Hold My Items' first-person arm redirect, which 26.3 must move instead of dropping (#390). */
public final class Test90FirstPersonArmMixin implements Test {

    @Override
    public String description() {
        return "first-person arm redirect from 1.21.11 applies, on 26.3 to the split renderer";
    }

    @Override
    public TestResult run() {
        // Joined at run time, so a remapper cannot point these names at another class.
        String[] renderers = {String.join(".", "net", "minecraft", "client", "renderer", "FirstPersonHandsAndItemsRenderer"),
                String.join(".", "net", "minecraft", "client", "renderer", "ItemInHandRenderer"),
                String.join(".", "net", "minecraft", "class_759")};
        boolean collectorHost = false;
        for (String collector : new String[]{String.join(".", "net", "minecraft", "client", "renderer", "SubmitNodeCollector"),
                String.join(".", "net", "minecraft", "class_11659")}) {
            collectorHost |= loads(collector) != null;
        }
        if (!collectorHost) return TestResult.success();
        // Loading the renderer applies the mixin; require = 1 stops the game there when its redirect matches nothing.
        for (String name : renderers) {
            Class<?> renderer = loads(name);
            if (renderer == null) continue;
            try {
                renderer.getDeclaredField("retromodTest$armRedirect");
                return TestResult.success();
            } catch (NoSuchFieldException e) {
                return TestResult.fail("the arm redirect mixin did not apply to " + name);
            }
        }
        return TestResult.fail("no first-person renderer class loaded");
    }

    private static Class<?> loads(String name) {
        try {
            return Class.forName(name, false, Test90FirstPersonArmMixin.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
