/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

/** Stands in for {@code RenderType}, remembering what {@link RenderTypes#armorTrim} was given. */
public final class RenderType {
    public final Identifier texture;
    public final boolean decal;

    RenderType(Identifier texture, boolean decal) {
        this.texture = texture;
        this.decal = decal;
    }
}
