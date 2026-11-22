/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.entity.player.PlayerInventory;

/** Load-time regression for a constructor inject retyped to the host's constructor (#425). */
public class Test88ConstructorInjectFollowsHost implements Test {

    @Override
    public String description() {
        return "constructor inject follows a constructor that gained a parameter";
    }

    @Override
    public TestResult run() {
        // Loading the class applies LegacyPlayerInventoryCtorMixin, whose require = 1 fails the load
        // when the handler or its refmap entry still names the 1.20.1 Inventory(Player).
        try {
            Class<?> inventory = PlayerInventory.class;
            return inventory.getDeclaredConstructors().length > 0
                    ? TestResult.success()
                    : TestResult.fail("PlayerInventory has no constructor");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
