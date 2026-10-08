/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Forge {@code IForgeRegistry} reads that have no same-shaped vanilla {@code Registry} method,
 * embedded per mod. The receiver is the vanilla registry that ForgeRegistries field reads now
 * resolve to, which is iterable over its values.
 */
public final class LegacyRegistryCalls {

    private LegacyRegistryCalls() {}

    /** Forge's {@code getValues()}: every registered value, in registration order. */
    public static Collection<Object> getValues(Object registry) {
        List<Object> values = new ArrayList<>();
        for (Object value : (Iterable<?>) registry) values.add(value);
        return Collections.unmodifiableList(values);
    }
}
