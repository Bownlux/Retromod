/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.util.Random;

/**
 * A {@code java.util.Random} that draws from a Minecraft {@code RandomSource}.
 *
 * <p>Minecraft 1.19 changed {@code Entity.random}, {@code Level.random}, and every
 * {@code getRandom()} from {@code java.util.Random} to its own {@code RandomSource}. A 1.18.2 mod
 * still reads them as {@code Random}, so the read is retyped and wrapped in this view. The view
 * keeps the game's own random stream, so seeded behavior stays with the entity or level.
 *
 * <p>Compiled against plain Java so the class can be embedded into any mod: the source's
 * {@code nextLong} is found by reflection, under whichever name the host uses for it.
 */
public final class LegacyRandomView extends Random {

    private static final long serialVersionUID = 1L;

    private final transient Object source;
    private final transient Method nextLong;

    private LegacyRandomView(Object source, Method nextLong) {
        this.source = source;
        this.nextLong = nextLong;
    }

    /** Wraps a {@code RandomSource}; null stays null. */
    public static Random wrap(Object source) {
        if (source == null) {
            return null;
        }
        if (source instanceof Random random) {
            return random;
        }
        return new LegacyRandomView(source, findNextLong(source.getClass()));
    }

    private static Method findNextLong(Class<?> type) {
        // RandomSource is usually reached through another interface, such as BitRandomSource.
        java.util.ArrayDeque<Class<?>> pending = new java.util.ArrayDeque<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            pending.add(current);
        }
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        while (!pending.isEmpty()) {
            Class<?> candidate = pending.poll();
            if (!seen.add(candidate)) {
                continue;
            }
            if (candidate.getName().equals("net.minecraft.util.RandomSource")) {
                for (Method method : candidate.getMethods()) {
                    if (method.getParameterCount() == 0 && method.getReturnType() == long.class) {
                        return method;
                    }
                }
            }
            pending.addAll(java.util.Arrays.asList(candidate.getInterfaces()));
        }
        throw new IllegalArgumentException("Not a Minecraft RandomSource: " + type.getName());
    }

    @Override
    protected int next(int bits) {
        if (source == null) {
            // Random's constructor calls setSeed before the fields exist; nothing draws then.
            return 0;
        }
        try {
            return (int) ((long) nextLong.invoke(source) >>> (64 - bits));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not draw from " + source.getClass().getName(), e);
        }
    }
}
