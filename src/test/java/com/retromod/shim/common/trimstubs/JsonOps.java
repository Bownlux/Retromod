/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

/** Stands in for DataFixerUpper's {@code JsonOps}, which the trim helper passes to a codec. */
public final class JsonOps implements DynamicOps {
    public static final JsonOps INSTANCE = new JsonOps();

    private JsonOps() {}
}
