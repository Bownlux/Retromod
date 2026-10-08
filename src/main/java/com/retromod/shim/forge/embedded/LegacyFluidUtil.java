/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Stand-in for the 1.12.2 {@code FluidUtil} helpers. 1.12 fluids are not registered with the host,
 * so no item holds one: a filled bucket is the empty stack and no item reports a contained fluid.
 * Returning the empty stack instead of null keeps a mod's cached bucket stacks safe to read.
 */
public final class LegacyFluidUtil {

    private LegacyFluidUtil() {}

    public static Object getFilledBucket(Object fluidStack) {
        LegacyForgeEvents.note("1.12 fluid buckets", fluidStack);
        return emptyItemStack();
    }

    /** Declares the stand-in stack as its result, as the call sites keep it after erasure. */
    public static LegacyFluidStack getFluidContained(Object itemStack) {
        return null;
    }

    public static Object getFluidHandler(Object target) {
        return null;
    }

    /**
     * {@code ItemStack.EMPTY}, found by type: its field name differs between SRG and Mojang hosts,
     * but it is the only static {@code ItemStack} field the class declares.
     */
    static Object emptyItemStack() {
        try {
            Class<?> stack = Class.forName("net.minecraft.world.item.ItemStack", false,
                    LegacyFluidUtil.class.getClassLoader());
            for (Field f : stack.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == stack) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // No ItemStack class (a test classpath); callers get null as before.
        }
        return null;
    }
}
