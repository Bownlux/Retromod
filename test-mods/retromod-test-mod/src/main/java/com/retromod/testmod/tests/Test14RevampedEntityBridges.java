/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.SimpleTest;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.registry.tag.EntityTypeTags;

/**
 * Exercises two entity API shapes used by Revamped Phantoms before the 26.x targeting rewrite.
 * The entity tag check is exposed separately because tags bind only after a world join.
 */
public final class Test14RevampedEntityBridges implements Test {

    @Override
    public String description() {
        return "legacy target predicate bridge";
    }

    @Override
    public TestResult run() {
        TargetPredicate predicate = TargetPredicate.createAttackable()
                .setPredicate(entity -> true);
        if (predicate == null) {
            return TestResult.fail("target predicate bridge returned null");
        }
        return TestResult.success();
    }

    public static Test entityTagMembership() {
        return new SimpleTest("legacy entity tag bridge", () -> {
            if (!EntityType.SKELETON.isIn(EntityTypeTags.SKELETONS)) {
                return TestResult.fail("skeleton entity tag membership was lost");
            }
            return TestResult.success();
        });
    }
}
