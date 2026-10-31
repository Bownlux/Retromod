/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Finishes a 1.21.1 explosion in a second step, as {@code finalizeExplosion} did, on hosts that explode in one call. */
public final class LegacyExplosionFinisher {

    // Drops of the same kind leave as one item while it holds no more than this many.
    private static final int MAX_MERGED_DROPS = 16;
    // Block flags that update neighbors and tell clients, as a plain block change does.
    private static final int UPDATE_ALL = 3;

    private LegacyExplosionFinisher() {}

    /** Plays the explosion on a client level; breaks, drops, and burns its blocks on a server level. */
    public static void finalizeExplosion(Object explosion, Object level, List<Object> toBlow, boolean interacts,
            boolean fire, boolean spawnParticles, Object sound, Object small, Object large, Object center, float radius) {
        try {
            Class<?> levelClass = type("net.minecraft.world.level.Level");
            Class<?> serverLevel = type("net.minecraft.server.level.ServerLevel");
            // Only a server level breaks blocks now; a client sees the result through block updates.
            if (!serverLevel.isInstance(level)) {
                playEffects(level, levelClass, interacts, spawnParticles, sound, small, large, center, radius);
                return;
            }
            if (interacts) breakBlocks(explosion, level, levelClass, serverLevel, toBlow);
            if (fire) lightFires(level, levelClass, toBlow);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not finish an old explosion", failure);
        }
    }

    private static void playEffects(Object level, Class<?> levelClass, boolean interacts, boolean spawnParticles,
            Object sound, Object small, Object large, Object center, float radius) throws ReflectiveOperationException {
        double x = center.getClass().getField("x").getDouble(center);
        double y = center.getClass().getField("y").getDouble(center);
        double z = center.getClass().getField("z").getDouble(center);
        Object random = levelClass.getMethod("getRandom").invoke(level);
        Method nextFloat = type("net.minecraft.util.RandomSource").getMethod("nextFloat");
        float pitch = (1.0F + ((float) nextFloat.invoke(random) - (float) nextFloat.invoke(random)) * 0.2F) * 0.7F;
        Class<?> soundEvent = type("net.minecraft.sounds.SoundEvent");
        Class<?> soundSource = type("net.minecraft.sounds.SoundSource");
        Object event = type("net.minecraft.core.Holder").getMethod("value").invoke(sound);
        levelClass.getMethod("playLocalSound", double.class, double.class, double.class, soundEvent, soundSource,
                float.class, float.class, boolean.class)
                .invoke(level, x, y, z, event, soundSource.getField("BLOCKS").get(null), 4.0F, pitch, false);
        if (!spawnParticles) return;
        // A large explosion that breaks blocks shows the emitter particle, a small one a single burst.
        Object particle = radius >= 2.0F && interacts ? large : small;
        levelClass.getMethod("addParticle", type("net.minecraft.core.particles.ParticleOptions"), double.class,
                double.class, double.class, double.class, double.class, double.class)
                .invoke(level, particle, x, y, z, 1.0, 0.0, 0.0);
    }

    private static void breakBlocks(Object explosion, Object level, Class<?> levelClass, Class<?> serverLevel,
            List<Object> toBlow) throws ReflectiveOperationException {
        Object profiler = type("net.minecraft.util.profiling.Profiler").getMethod("get").invoke(null);
        Class<?> profilerClass = type("net.minecraft.util.profiling.ProfilerFiller");
        profilerClass.getMethod("push", String.class).invoke(profiler, "explosion_blocks");
        try {
            Class<?> pos = type("net.minecraft.core.BlockPos");
            Class<?> random = type("net.minecraft.util.RandomSource");
            util().getMethod("shuffle", List.class, random).invoke(null, toBlow, levelClass.getMethod("getRandom").invoke(level));
            // Each block hands its drops to the collector, which merges stacks of the same kind.
            Method stateAt = levelClass.getMethod("getBlockState", pos);
            Method hit = type("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase").getMethod(
                    "onExplosionHit", serverLevel, pos, type("net.minecraft.world.level.Explosion"), BiConsumer.class);
            List<Object[]> drops = new ArrayList<>();
            BiConsumer<Object, Object> collector = (stack, at) -> collectDrop(drops, stack, at);
            for (Object at : new ArrayList<>(toBlow)) {
                hit.invoke(stateAt.invoke(level, at), level, at, explosion, collector);
            }
            Method popResource = type("net.minecraft.world.level.block.Block").getMethod("popResource", levelClass, pos,
                    type("net.minecraft.world.item.ItemStack"));
            for (Object[] drop : drops) popResource.invoke(null, level, drop[1], drop[0]);
        } finally {
            profilerClass.getMethod("pop").invoke(profiler);
        }
    }

    /** Adds a drop to an earlier one of the same item and components while it stays small, or keeps it apart. */
    private static void collectDrop(List<Object[]> drops, Object stack, Object at) {
        try {
            Class<?> itemStack = type("net.minecraft.world.item.ItemStack");
            Method sameKind = itemStack.getMethod("isSameItemSameComponents", itemStack, itemStack);
            Method count = itemStack.getMethod("getCount");
            int adding = (int) count.invoke(stack);
            for (Object[] drop : drops) {
                int held = (int) count.invoke(drop[0]);
                int limit = Math.min(MAX_MERGED_DROPS, (int) itemStack.getMethod("getMaxStackSize").invoke(drop[0]));
                if ((boolean) sameKind.invoke(null, drop[0], stack) && held + adding <= limit) {
                    drop[0] = itemStack.getMethod("copyWithCount", int.class).invoke(drop[0], held + adding);
                    return;
                }
            }
            drops.add(new Object[]{stack, at});
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not collect an explosion drop", failure);
        }
    }

    /** Sets fire to a third of the cleared spots that sit on a solid block. */
    private static void lightFires(Object level, Class<?> levelClass, List<Object> toBlow) throws ReflectiveOperationException {
        Class<?> pos = type("net.minecraft.core.BlockPos");
        Class<?> state = type("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
        Class<?> blockState = type("net.minecraft.world.level.block.state.BlockState");
        Object random = levelClass.getMethod("getRandom").invoke(level);
        Method nextInt = type("net.minecraft.util.RandomSource").getMethod("nextInt", int.class);
        Method stateAt = levelClass.getMethod("getBlockState", pos);
        Method fireAt = type("net.minecraft.world.level.block.BaseFireBlock").getMethod("getState",
                type("net.minecraft.world.level.BlockGetter"), pos);
        Method place = levelClass.getMethod("setBlock", pos, blockState, int.class);
        for (Object at : toBlow) {
            if ((int) nextInt.invoke(random, 3) != 0) continue;
            Object below = pos.getMethod("below").invoke(at);
            if ((boolean) state.getMethod("isAir").invoke(stateAt.invoke(level, at))
                    && (boolean) state.getMethod("isSolidRender").invoke(stateAt.invoke(level, below))) {
                place.invoke(level, at, fireAt.invoke(null, level, at), UPDATE_ALL);
            }
        }
    }

    /** {@code Util}, which newer versions keep in {@code net.minecraft.util} and older ones in {@code net.minecraft}. */
    private static Class<?> util() throws ClassNotFoundException {
        try {
            return type("net.minecraft.util.Util");
        } catch (ClassNotFoundException moved) {
            return type("net.minecraft.Util");
        }
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, true, LegacyExplosionFinisher.class.getClassLoader());
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }
}
