/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

/** Minecraft 1.21.1's GUI sprite manager, read from the GUI atlas the atlas manager holds now. */
public final class LegacyGuiSpriteManager {

    private static final LegacyGuiSpriteManager INSTANCE = new LegacyGuiSpriteManager();

    private LegacyGuiSpriteManager() {}

    /** {@code minecraft.getGuiSprites()}. */
    public static LegacyGuiSpriteManager of(Minecraft minecraft) {
        return INSTANCE;
    }

    /** The GUI sprite with this id, or the missing sprite, as the old manager answered. */
    public TextureAtlasSprite getSprite(Identifier id) {
        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.GUI).getSprite(id);
    }

    /** How the sprite stretches, from its metadata, or a plain stretch when it has none. */
    public GuiSpriteScaling getSpriteScaling(TextureAtlasSprite sprite) {
        return sprite.contents().getAdditionalMetadata(GuiMetadataSection.TYPE).orElse(GuiMetadataSection.DEFAULT).scaling();
    }
}
