/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;

/** The block-atlas entity render types the old {@code Sheets} handed out, rebuilt from the entity render types. */
public final class LegacyBlockSheets {

    private LegacyBlockSheets() {}

    /** {@code Sheets.solidBlockSheet()}. */
    public static RenderType solidBlockSheet() {
        return RenderTypes.entitySolid(TextureAtlas.LOCATION_BLOCKS);
    }

    /** {@code Sheets.cutoutBlockSheet()}. */
    public static RenderType cutoutBlockSheet() {
        return RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS);
    }

    /** {@code Sheets.translucentCullBlockSheet()} on a host without a culled translucent type, which draws back faces too. */
    public static RenderType translucentBlockSheet() {
        return RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
    }
}
