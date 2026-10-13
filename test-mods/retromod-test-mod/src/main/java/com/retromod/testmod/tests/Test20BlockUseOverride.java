/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Regression test for #338: a pre-1.20.5 block's right-click override.
 *
 * <p>1.20.5 split {@code Block.onUse} into {@code useWithoutItem} and {@code useItemOn}, so the
 * override below was never called on newer hosts, and its {@code super.onUse} named a method the
 * parent no longer has. Retromod adds a {@code useWithoutItem} that calls the old method with the
 * main hand. The test calls that new method, which the game itself calls on a right click, and
 * checks that the old override ran and its super call resolved.
 *
 * <p>The block is allocated without its constructor, because 1.21.2 and newer refuse to build a
 * block whose properties carry no registry id. Nothing here reads the block's fields.
 */
public class Test20BlockUseOverride implements Test {
    @Override
    public String description() {
        return "#338 pre-1.20.5 Block.onUse override";
    }

    @Override
    public TestResult run() {
        try {
            UseBlock block = allocate(UseBlock.class);
            Method modern = fiveArgumentUse(UseBlock.class);
            if (modern == null) {
                // A host before 1.20.5 still calls onUse itself, so there is nothing to bridge.
                return hostHasSplitUse() ? TestResult.fail("no useWithoutItem was added for the old onUse override")
                        : TestResult.success();
            }
            modern.setAccessible(true);
            modern.invoke(block, null, null, null, null, null);
            return block.ran ? TestResult.success() : TestResult.fail("the old onUse override did not run");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return TestResult.fail(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /** The added method takes the old parameters without the hand, so it is the only five-argument one. */
    private static Method fiveArgumentUse(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() == 5 && method.getReturnType() == ActionResult.class) return method;
        }
        return null;
    }

    private static boolean hostHasSplitUse() {
        for (Class<?> type = Block.class; type != null; type = type.getSuperclass()) {
            if (fiveArgumentUse(type) != null) return true;
        }
        return false;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return type.cast(unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    static class UseBlock extends Block {
        boolean ran;

        UseBlock(Settings settings) {
            super(settings);
        }

        @Override
        @SuppressWarnings("deprecation")
        public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand,
                                  BlockHitResult hit) {
            ActionResult parent = super.onUse(state, world, pos, player, hand, hit);
            ran = hand == Hand.MAIN_HAND;
            return parent;
        }
    }
}
