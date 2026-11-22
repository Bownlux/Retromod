/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.model.pipeline;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.neoforged.neoforge.client.model.pipeline.QuadBakingVertexConsumer;

/** NeoForge 1.21.1's {@code QuadBakingVertexConsumer.setSprite(sprite)}, which 26.1 takes as a baked material. */
public final class LegacyQuadBaking {

    private LegacyQuadBaking() {}

    /** The sprite as a material that is not forced translucent, the old default. */
    public static void setSprite(QuadBakingVertexConsumer consumer, TextureAtlasSprite sprite) {
        consumer.setSprite(new Material.Baked(sprite, false));
    }
}
