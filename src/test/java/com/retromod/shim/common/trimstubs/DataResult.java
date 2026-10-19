/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

import java.util.Optional;

/** Stands in for DataFixerUpper's {@code DataResult}: a decoded value, or none. */
public final class DataResult {
    private final Object value;

    private DataResult(Object value) {
        this.value = value;
    }

    public static DataResult success(Object value) {
        return new DataResult(value);
    }

    public Optional<Object> result() {
        return Optional.ofNullable(value);
    }
}
