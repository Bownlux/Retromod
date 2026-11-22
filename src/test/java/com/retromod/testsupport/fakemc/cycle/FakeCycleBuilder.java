/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.cycle;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Stands in for 26.x {@code CycleButton.Builder}: its initial value comes from a supplier, read when the button is made. */
public class FakeCycleBuilder<T> {
    private final Supplier<T> defaultValueSupplier;
    private FakeValueList<T> values = () -> List.of();

    public FakeCycleBuilder(Function<T, Object> stringifier, Supplier<T> defaultValueSupplier) {
        this.defaultValueSupplier = defaultValueSupplier;
    }

    /** {@code withValues(list)}. */
    public FakeCycleBuilder<T> withValues(List<T> list) {
        values = () -> list;
        return this;
    }

    /** The value {@code create} starts the button on. */
    public T create() {
        return defaultValueSupplier.get();
    }
}
