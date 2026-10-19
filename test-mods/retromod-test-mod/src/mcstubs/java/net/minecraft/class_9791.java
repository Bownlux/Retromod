/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package net.minecraft;

import net.minecraft.registry.RegistryKey;

import java.util.Optional;

/**
 * Compile-time stand-in for 1.21.11's {@code EitherHolder}, under its intermediary name, which this
 * 1.20.1 build cannot name otherwise. It is not packaged, so the test mod's calls keep the
 * intermediary names a 1.21.11 Fabric mod carries.
 */
public final class class_9791<T> {

    public class_9791(RegistryKey<T> key) {
        throw new AssertionError("compile-time stand-in");
    }

    /** {@code key()}. */
    public Optional<RegistryKey<T>> method_67385() {
        throw new AssertionError("compile-time stand-in");
    }
}
