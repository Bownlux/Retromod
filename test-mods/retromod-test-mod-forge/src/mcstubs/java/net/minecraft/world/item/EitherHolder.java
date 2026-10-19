/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package net.minecraft.world.item;

import net.minecraft.resources.ResourceKey;

import java.util.Optional;

/**
 * Compile-time stand-in for 1.21.11's {@code EitherHolder}, which this 1.20.1 build cannot name
 * otherwise. It is not packaged, so the test mod's calls keep the names a 1.21.11 mod carries.
 */
public final class EitherHolder<T> {

    public EitherHolder(ResourceKey<T> key) {
        throw new AssertionError("compile-time stand-in");
    }

    public Optional<ResourceKey<T>> key() {
        throw new AssertionError("compile-time stand-in");
    }
}
