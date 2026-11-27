/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui.components;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** An image button drawn from a region of a texture, as ImageButton was until 1.20.2 switched it to GUI sprites. */
public class LegacyImageButton extends ImageButton {

    // Null for a button built with sprites, which draws as the host's ImageButton does.
    private final Identifier texture;
    private final int u;
    private final int v;
    private final int hoverOffset;
    private final int textureWidth;
    private final int textureHeight;

    /** {@code new ImageButton(x, y, width, height, u, v, texture, onPress)}: the hovered image sits one button height down. */
    public LegacyImageButton(int x, int y, int width, int height, int u, int v, Identifier texture, Button.OnPress onPress) {
        this(x, y, width, height, u, v, height, texture, onPress);
    }

    /** {@code new ImageButton(x, y, width, height, u, v, hoverOffset, texture, onPress)}, from a 256 pixel texture. */
    public LegacyImageButton(int x, int y, int width, int height, int u, int v, int hoverOffset, Identifier texture,
            Button.OnPress onPress) {
        this(x, y, width, height, u, v, hoverOffset, texture, 256, 256, onPress, CommonComponents.EMPTY);
    }

    /** The same with the texture's size. */
    public LegacyImageButton(int x, int y, int width, int height, int u, int v, int hoverOffset, Identifier texture,
            int textureWidth, int textureHeight, Button.OnPress onPress) {
        this(x, y, width, height, u, v, hoverOffset, texture, textureWidth, textureHeight, onPress, CommonComponents.EMPTY);
    }

    /** The same with a message for narration. */
    public LegacyImageButton(int x, int y, int width, int height, int u, int v, int hoverOffset, Identifier texture,
            int textureWidth, int textureHeight, Button.OnPress onPress, Component message) {
        // The sprites are never drawn: the texture region below takes their place.
        super(x, y, width, height, new WidgetSprites(texture), onPress, message);
        this.texture = texture;
        this.u = u;
        this.v = v;
        this.hoverOffset = hoverOffset;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
    }

    // The host's own constructors, for a subclass moved onto this class that already uses sprites.
    public LegacyImageButton(int x, int y, int width, int height, WidgetSprites sprites, Button.OnPress onPress) {
        this(x, y, width, height, sprites, onPress, CommonComponents.EMPTY);
    }

    public LegacyImageButton(int x, int y, WidgetSprites sprites, Button.OnPress onPress, Component message) {
        super(x, y, sprites, onPress, message);
        this.texture = null;
        this.u = this.v = this.hoverOffset = this.textureWidth = this.textureHeight = 0;
    }

    public LegacyImageButton(int x, int y, int width, int height, WidgetSprites sprites, Button.OnPress onPress, Component message) {
        super(x, y, width, height, sprites, onPress, message);
        this.texture = null;
        this.u = this.v = this.hoverOffset = this.textureWidth = this.textureHeight = 0;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (texture == null) {
            super.extractContents(graphics, mouseX, mouseY, partialTick);
            return;
        }
        // The rows below the normal image held the hovered image and, one more row down, the disabled one.
        int row = v;
        if (!isActive()) {
            row += hoverOffset * 2;
        } else if (isHoveredOrFocused()) {
            row += hoverOffset;
        }
        int x = getX();
        int y = getY();
        graphics.blit(texture, x, y, x + width, y + height, (float) u / textureWidth, (float) (u + width) / textureWidth,
                (float) row / textureHeight, (float) (row + height) / textureHeight);
    }

    /** 1.20.1's {@code updateTooltip()}, which a mod's override calls as super; the host's tooltip holder does that work now. */
    public void updateTooltip() {
    }

    /** Factories for {@code new ImageButton(...)} with the old arguments. */
    public static ImageButton create(int x, int y, int width, int height, int u, int v, Identifier texture, Button.OnPress onPress) {
        return new LegacyImageButton(x, y, width, height, u, v, texture, onPress);
    }

    public static ImageButton create(int x, int y, int width, int height, int u, int v, int hoverOffset, Identifier texture,
            Button.OnPress onPress) {
        return new LegacyImageButton(x, y, width, height, u, v, hoverOffset, texture, onPress);
    }

    public static ImageButton create(int x, int y, int width, int height, int u, int v, int hoverOffset, Identifier texture,
            int textureWidth, int textureHeight, Button.OnPress onPress) {
        return new LegacyImageButton(x, y, width, height, u, v, hoverOffset, texture, textureWidth, textureHeight, onPress);
    }

    public static ImageButton create(int x, int y, int width, int height, int u, int v, int hoverOffset, Identifier texture,
            int textureWidth, int textureHeight, Button.OnPress onPress, Component message) {
        return new LegacyImageButton(x, y, width, height, u, v, hoverOffset, texture, textureWidth, textureHeight, onPress, message);
    }
}
