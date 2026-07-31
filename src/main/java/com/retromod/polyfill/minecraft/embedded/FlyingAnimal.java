/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Polyfill for net.minecraft.world.entity.animal.FlyingAnimal, removed in MC 26.2.
 * A single-method marker interface (boolean isFlying()) that flying-mob content
 * mods implement on their custom entity. On 26.2 the interface is gone, so a mod
 * class `implements FlyingAnimal` fails to load with NoClassDefFoundError before
 * any code runs. Nothing in 26.2 MC consumes the interface (it was removed, not
 * moved: FlyingPathNavigation/FlyingMoveControl are unrelated survivors), so there
 * is no real-vs-polyfill boundary to cross; providing the interface simply lets the
 * mob class load instead of crashing.
 *
 * Mods referencing net/minecraft/world/entity/animal/FlyingAnimal (or intermediary
 * class_1432) are redirected here by Minecraft26_2RemovedPolyfill, gated to 26.2+
 * hosts. The original interface still exists on 26.1 and earlier, so the redirect is
 * limited to 26.2+.
 */
package com.retromod.polyfill.minecraft.embedded;

/**
 * Drop-in replacement for the removed {@code net.minecraft.world.entity.animal.FlyingAnimal}.
 * The mod's overriding {@code isFlying()} matches this single abstract method.
 */
public interface FlyingAnimal {

    /** Whether the entity is currently flying. */
    boolean isFlying();
}
