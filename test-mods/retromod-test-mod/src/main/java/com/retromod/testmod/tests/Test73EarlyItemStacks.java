/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.SimpleTest;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;

/** Item stacks built while the mod initializes, which 26.x cannot build until a world's data binds item components. */
public final class Test73EarlyItemStacks implements Test {

    // Kept from common init for the server-started half.
    private static ItemStack diamonds;
    private static ItemStack pickaxe;
    private static ItemStack defaultApple;
    private static ItemStack copied;

    @Override
    public String description() {
        return "new ItemStack(item, count), new ItemStack(entry) and getDefaultStack() while the mod initializes";
    }

    @Override
    public TestResult run() {
        try {
            diamonds = new ItemStack(Items.DIAMOND, 3);
            pickaxe = new ItemStack(Items.DIAMOND_PICKAXE.getRegistryEntry());
            defaultApple = Items.APPLE.getDefaultStack();
            copied = diamonds.copy();
            copied.setCount(5);
        } catch (Throwable t) {
            return TestResult.fail("building threw " + t);
        }
        if (diamonds.getItem() != Items.DIAMOND || diamonds.getCount() != 3 || diamonds.isEmpty()) {
            return TestResult.fail("the stack reads " + diamonds.getCount() + " " + diamonds.getItem());
        }
        if (defaultApple.isEmpty() || defaultApple.getItem() != Items.APPLE) {
            return TestResult.fail("getDefaultStack() gave " + defaultApple);
        }
        return pickaxe.getItem() == Items.DIAMOND_PICKAXE ? TestResult.success() : TestResult.fail("pickaxe reads " + pickaxe);
    }

    /** The server-started half: the stacks kept from init read their defaults and stack with stacks built now. */
    public static Test checkOnServer(MinecraftServer server) {
        return new SimpleTest("the item stacks built during init stack with stacks built once the server's data loaded", () -> {
            if (diamonds == null) return TestResult.fail("the init half did not run");
            ItemStack now = new ItemStack(Items.DIAMOND, 3);
            if (diamonds.getMaxCount() != 64) return TestResult.fail("max count reads " + diamonds.getMaxCount());
            if (!ItemStack.canCombine(diamonds, now) || !ItemStack.canCombine(now, diamonds)) {
                return TestResult.fail("the init stack does not stack with a new one");
            }
            if (!ItemStack.areEqual(copied, new ItemStack(Items.DIAMOND, 5))) {
                return TestResult.fail("the copy made during init does not equal a new stack of five");
            }
            if (!pickaxe.isDamageable() || !ItemStack.canCombine(pickaxe, new ItemStack(Items.DIAMOND_PICKAXE))) {
                return TestResult.fail("the pickaxe built from its entry lost its defaults");
            }
            return ItemStack.canCombine(defaultApple, new ItemStack(Items.APPLE))
                    ? TestResult.success() : TestResult.fail("getDefaultStack() during init does not stack with an apple");
        });
    }
}
