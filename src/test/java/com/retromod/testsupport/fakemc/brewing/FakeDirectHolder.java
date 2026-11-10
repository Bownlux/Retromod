/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.brewing;

/** A holder that keeps its value. */
public final class FakeDirectHolder implements FakeHolder {
    private final Object value;

    public FakeDirectHolder(Object value) {
        this.value = value;
    }

    @Override
    public Object value() {
        return value;
    }
}
