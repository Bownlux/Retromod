/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;

/** {@code VertexFormat.getOffset(element)} on 26.3, where the vertex format moved to the renderpearl package. */
public final class LegacyPearlVertexOffsets {

    private LegacyPearlVertexOffsets() {}

    /** The offset of the format's element with the same name, or -1 when the format has none, as the old call answered. */
    public static int getOffset(VertexFormat format, VertexFormatElement element) {
        VertexFormatElement own = format.getElement(element.name());
        return own == null ? -1 : own.offset();
    }
}
