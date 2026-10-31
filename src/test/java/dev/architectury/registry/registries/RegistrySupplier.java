/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package dev.architectury.registry.registries;

import java.util.function.Consumer;

/** Test stand-in for Architectury's RegistrySupplier. */
public interface RegistrySupplier<T> extends DeferredSupplier<T> {
    Object getRegistrar();

    default void listen(Consumer<T> callback) {
        throw new UnsupportedOperationException("listen needs the registrar");
    }
}
