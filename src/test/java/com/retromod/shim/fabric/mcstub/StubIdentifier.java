/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.mcstub;

/** Stands in for {@code net.minecraft.resources.Identifier} in bridge behavior tests. */
public final class StubIdentifier {
    private final String value;

    private StubIdentifier(String value) {
        this.value = value;
    }

    public static StubIdentifier tryParse(String value) {
        return value.indexOf(':') > 0 ? new StubIdentifier(value) : null;
    }

    @Override
    public String toString() {
        return value;
    }
}
