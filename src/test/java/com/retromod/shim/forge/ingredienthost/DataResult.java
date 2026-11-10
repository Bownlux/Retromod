/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

import java.util.Optional;
import java.util.function.Supplier;

/** Stands in for DataFixerUpper's DataResult: a value or an error message. */
public interface DataResult<R> {
    static <R> DataResult<R> success(R value) {
        return new Result<>(value, null);
    }

    static <R> DataResult<R> error(Supplier<String> message) {
        return new Result<>(null, message.get());
    }

    Optional<R> result();

    String message();

    record Result<R>(R value, String error) implements DataResult<R> {
        @Override
        public Optional<R> result() {
            return Optional.ofNullable(value);
        }

        @Override
        public String message() {
            return error;
        }
    }
}
