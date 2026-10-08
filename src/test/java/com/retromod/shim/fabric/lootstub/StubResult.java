/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.lootstub;

import java.util.Optional;

/** The two {@code DataResult} accessors the loot adapter reads. */
public final class StubResult {
    private final Object value;
    private final String error;

    private StubResult(Object value, String error) {
        this.value = value;
        this.error = error;
    }

    public static StubResult success(Object value) {
        return new StubResult(value, null);
    }

    public static StubResult failure(String message) {
        return new StubResult(null, message);
    }

    public Optional<Object> result() {
        return Optional.ofNullable(value);
    }

    public Optional<Failure> error() {
        return error == null ? Optional.empty() : Optional.of(new Failure(error));
    }

    /** {@code DataResult.Error}, reduced to its message. */
    public record Failure(String message) {}
}
