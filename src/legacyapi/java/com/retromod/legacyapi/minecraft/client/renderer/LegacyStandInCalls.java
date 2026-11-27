/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.retromod.shim.common.embedded.MultiBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.function.Function;

/** Old calls that took or made a buffer source or read a sprite from its id, rebuilt on the 26.x render classes. */
public final class LegacyStandInCalls {

    // A mouse position no widget covers, for graphics built outside a screen.
    private static final int NO_MOUSE = -1;

    private LegacyStandInCalls() {}

    /** {@code font.drawInBatch(text, ...)} on a host without it: draws nothing, but returns the end x old layout code reads. */
    public static int drawInBatch(Font font, String text, float x, float y, int color, boolean shadow, Object pose,
            Object buffers, Object mode, int background, int light) {
        return endX(x, font.width(text), shadow);
    }

    public static int drawInBatch(Font font, Component text, float x, float y, int color, boolean shadow, Object pose,
            Object buffers, Object mode, int background, int light) {
        return endX(x, font.width(text), shadow);
    }

    public static int drawInBatch(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow,
            Object pose, Object buffers, Object mode, int background, int light) {
        return endX(x, font.width(text), shadow);
    }

    // The old draw returned the x after the last glyph, one pixel further with a shadow.
    private static int endX(float x, int width, boolean shadow) {
        return (int) (x + width) + (shadow ? 1 : 0);
    }

    /** {@code new GuiGraphics(minecraft, buffers)}: graphics over a render state of their own, which no frame draws. */
    public static GuiGraphicsExtractor graphics(Minecraft minecraft, Object buffers) {
        return new GuiGraphicsExtractor(minecraft, new GuiRenderState(), NO_MOUSE, NO_MOUSE);
    }

    /** {@code new GuiGraphics(minecraft, pose, buffers)}: the same; the new graphics keep their own pose. */
    public static GuiGraphicsExtractor graphics(Minecraft minecraft, Object pose, Object buffers) {
        return graphics(minecraft, buffers);
    }

    /** {@code material.sprite()}: the sprite the atlas holds for this id. */
    public static TextureAtlasSprite sprite(SpriteId id) {
        return Minecraft.getInstance().getAtlasManager().get(id);
    }

    /** {@code material.buffer(buffers, types)}: the render type's buffer, with texture coordinates mapped into the sprite. */
    public static VertexConsumer buffer(SpriteId id, MultiBufferSource buffers, Function<Identifier, RenderType> types) {
        return sprite(id).wrap(buffers.getBuffer(id.renderType(types)));
    }
}
