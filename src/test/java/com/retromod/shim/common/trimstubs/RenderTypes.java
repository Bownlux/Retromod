/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

/** Stands in for 26.3's {@code RenderTypes}, of which the trim helper uses {@code armorTrim}. */
public final class RenderTypes {
    private RenderTypes() {}

    public static RenderType armorTrim(Identifier texture, boolean decal) {
        return new RenderType(texture, decal);
    }
}
