/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.loader.api.FabricLoader;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

/** A bone turned through GeckoLib 4.0's CoreGeoBone interface, which GeckoLib 4.5 merged into the GeoBone class (#404). */
public final class Test92GeckoLibBone implements Test {

    /** Whether the installed GeckoLib is GeckoLib 4, the only one with this bone API; the case is left out otherwise. */
    public static boolean applies() {
        return FabricLoader.getInstance().getModContainer("geckolib")
                .map(container -> container.getMetadata().getVersion().getFriendlyString().startsWith("4."))
                .orElse(false);
    }

    @Override
    public String description() {
        return "GeckoLib 4 bone turned through CoreGeoBone";
    }

    @Override
    public TestResult run() {
        // A 1.20.1 model calls setRotX on the interface; on GeckoLib 4.5 and newer that is a class call.
        try {
            CoreGeoBone bone = new GeoBone(null, "head", false, 0.0, false, false);
            bone.setRotX(0.5F);
            return bone.getRotX() == 0.5F ? TestResult.success() : TestResult.fail("the bone turned to " + bone.getRotX());
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
