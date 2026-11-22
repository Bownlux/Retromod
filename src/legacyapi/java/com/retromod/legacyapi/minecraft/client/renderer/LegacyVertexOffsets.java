/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

/** {@code VertexFormat.getOffset(element)} on 26.2, where each format keeps its own elements with their offsets. */
public final class LegacyVertexOffsets {

    private LegacyVertexOffsets() {}

    /** The offset of the format's element with the same name, or -1 when the format has none, as the old call answered. */
    public static int getOffset(VertexFormat format, VertexFormatElement element) {
        VertexFormatElement own = format.getElement(element.name());
        return own == null ? -1 : own.offset();
    }
}
