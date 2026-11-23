/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common.extensions;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.function.ToIntFunction;

/** Runs an old mod item's own {@code getBurnTime(ItemStack, RecipeType)}, which no host after NeoForge 21.1 calls. */
public final class LegacyBurnTimeOverrides {

    // Each item class's old override, looked up once.
    private static final ClassValue<Optional<Method>> OVERRIDES = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            return findOverride(type);
        }
    };

    private LegacyBurnTimeOverrides() {}

    /** The item's own burn time for {@code stack}, else {@code fallback}'s answer, as NeoForge 21.1 read it. */
    public static int burnTime(Item item, ItemStack stack, RecipeType<?> type, ToIntFunction<ItemStack> fallback) {
        Optional<Method> override = item == null ? Optional.empty() : OVERRIDES.get(item.getClass());
        if (override.isEmpty()) return fallback.applyAsInt(stack);
        try {
            return (Integer) override.get().invoke(item, stack, type);
        } catch (InvocationTargetException failed) {
            throw rethrow(failed.getCause());
        } catch (IllegalAccessException unreachable) {
            return fallback.applyAsInt(stack);
        }
    }

    /** The public {@code getBurnTime(ItemStack, RecipeType)} a mod class declares, if any. */
    static Optional<Method> findOverride(Class<?> type) {
        try {
            Method method = type.getMethod("getBurnTime", ItemStack.class, RecipeType.class);
            // A host that still declares it calls it itself; only a mod's own method needs this.
            boolean modOwned = !method.getDeclaringClass().getName().startsWith("net.minecraft.")
                    && !method.getDeclaringClass().getName().startsWith("net.neoforged.");
            boolean usable = method.getReturnType() == int.class && !Modifier.isStatic(method.getModifiers())
                    && !Modifier.isAbstract(method.getModifiers());
            if (!modOwned || !usable) return Optional.empty();
            method.setAccessible(true);
            return Optional.of(method);
        } catch (NoSuchMethodException | RuntimeException none) {
            return Optional.empty();
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException("An old burn time override failed", cause);
    }
}
