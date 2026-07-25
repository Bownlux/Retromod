/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/**
 * 1.3.0: helper for the {@code TextureSheetParticle} -> {@code SingleQuadParticle} rebase (6 corpus
 * mods; the 26.1 particle rework deleted the old base). {@code pickSprite(SpriteSet)} did not
 * survive on the new base: this replacement picks a random sprite from the set
 * ({@code SpriteSet.get(RandomSource)}, verified present on both 26.x jars) and stores it in the
 * particle's protected {@code sprite} field (declared on SingleQuadParticle).
 *
 * <p>Reflective and fail-safe: needs {@code setAccessible} for the protected member (fine on
 * Fabric's unnamed-module runtime; on a sealed JPMS module it fails closed to a no-op, leaving the
 * particle invisible rather than crashing construction).
 */
public final class RetroParticleCompat {

    private RetroParticleCompat() {}

    private static volatile java.lang.reflect.Method spriteSetGet;
    private static volatile Object randomSource;

    /** Replacement for {@code TextureSheetParticle.pickSprite(SpriteSet)} (receiver as arg 0). */
    public static void pickSprite(Object particle, Object spriteSet) {
        try {
            if (particle == null || spriteSet == null) return;
            java.lang.reflect.Method get = spriteSetGet;
            Object random = randomSource;
            if (get == null || random == null) {
                Class<?> randomCls = Class.forName("net.minecraft.util.RandomSource");
                random = randomCls.getMethod("create").invoke(null);
                get = Class.forName("net.minecraft.client.particle.SpriteSet")
                        .getMethod("get", randomCls);
                spriteSetGet = get;
                randomSource = random;
            }
            Object sprite = get.invoke(spriteSet, random);
            if (sprite == null) return;
            // Store into the protected `sprite` field, walking up to its declaring class.
            for (Class<?> c = particle.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("sprite");
                    f.setAccessible(true);
                    f.set(particle, sprite);
                    return;
                } catch (NoSuchFieldException walkUp) {
                    // keep climbing
                }
            }
        } catch (Throwable t) {
            // soft-fail: the particle stays sprite-less (invisible), never a crash
        }
    }
}
