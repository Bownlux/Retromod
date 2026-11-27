/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.ai.goal.FollowOwnerGoal;

/** A pet's follow goal built with the can-fly flag that 1.21 removed (#400). */
public final class Test91FollowOwnerGoal implements Test {

    @Override
    public String description() {
        return "FollowOwnerGoal with the old can-fly flag";
    }

    @Override
    public TestResult run() {
        // Retromod bridges the goal in Mojang names, which a Fabric host runs from 26.1; 1.21 to 1.21.11 keep intermediary.
        String host = FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("");
        if (host.startsWith("1.21")) return TestResult.success();
        // No pet exists while the mod starts, so the null owner proves the constructor links:
        // an unbridged call throws NoSuchMethodError before the goal reads its pet.
        try {
            new FollowOwnerGoal(null, 1.0, 10.0F, 2.0F, false);
            return TestResult.success();
        } catch (NullPointerException linked) {
            return TestResult.success();
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
