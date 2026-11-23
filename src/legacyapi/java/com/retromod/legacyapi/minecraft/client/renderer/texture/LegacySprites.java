/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.texture;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.server.packs.resources.ResourceMetadata;

/** Old sprite calls on 1.21.11 and 26.x: the metadata-based SpriteContents arguments and the missing texture. */
public final class LegacySprites {

    // One missing texture per mod copy, made on first use like the old lazily created one.
    private static DynamicTexture missingTexture;

    private LegacySprites() {}

    /** The animation section the old constructor read from the metadata itself. */
    public static Optional<AnimationMetadataSection> animation(ResourceMetadata metadata) {
        return metadata == null ? Optional.empty() : metadata.getSection(AnimationMetadataSection.TYPE);
    }

    /** The texture section, which the new constructor takes on its own. */
    public static Optional<TextureMetadataSection> texture(ResourceMetadata metadata) {
        return metadata == null ? Optional.empty() : metadata.getSection(TextureMetadataSection.TYPE);
    }

    /** Extra mod metadata sections, which the old constructor had no way to receive. */
    public static List<?> additionalSections(ResourceMetadata metadata) {
        return List.of();
    }

    /** {@code MissingTextureAtlasSprite.getTexture()}: a texture holding the purple and black image. */
    public static synchronized DynamicTexture missingTexture() {
        if (missingTexture == null) {
            missingTexture = new DynamicTexture(() -> "retromod_missing_texture", MissingTextureAtlasSprite.generateMissingImage());
        }
        return missingTexture;
    }
}
