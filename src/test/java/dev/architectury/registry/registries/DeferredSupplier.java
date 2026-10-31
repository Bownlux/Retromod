/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package dev.architectury.registry.registries;

import java.util.function.Supplier;

/** Test stand-in for Architectury's DeferredSupplier, with ids as plain strings. */
public interface DeferredSupplier<T> extends Supplier<T> {
    String getRegistryId();

    String getId();

    boolean isPresent();

    default T getOrNull() {
        return isPresent() ? get() : null;
    }
}
