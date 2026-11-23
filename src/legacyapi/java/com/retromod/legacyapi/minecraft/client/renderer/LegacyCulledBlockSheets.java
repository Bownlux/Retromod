/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;

/** {@code Sheets.translucentCullBlockSheet()} on a host that has the culled translucent entity type again, as 26.3 does. */
public final class LegacyCulledBlockSheets {

    private LegacyCulledBlockSheets() {}

    /** {@code Sheets.translucentCullBlockSheet()}. */
    public static RenderType translucentCullBlockSheet() {
        return RenderTypes.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS);
    }
}
