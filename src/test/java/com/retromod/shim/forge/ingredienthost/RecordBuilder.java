/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

/** Stands in for DataFixerUpper's RecordBuilder: fields added by name, and errors. */
public interface RecordBuilder<T> {
    RecordBuilder<T> add(String key, T value);

    RecordBuilder<T> withErrorsFrom(DataResult<?> result);
}
