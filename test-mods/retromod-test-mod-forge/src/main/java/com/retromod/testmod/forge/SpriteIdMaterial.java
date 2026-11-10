/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;

/** Builds the atlas and texture {@code Material}, which 26.1 renamed {@code SpriteId}. Client only. */
final class SpriteIdMaterial {

    private SpriteIdMaterial() {}

    /** A Material reads back the atlas and texture it was built from. */
    static boolean atlasAndTextureReadBack() {
        ResourceLocation atlas = new ResourceLocation("minecraft", "textures/atlas/blocks.png");
        ResourceLocation texture = new ResourceLocation("retromod_test_mod_forge", "block/test_sprite");
        Material material = new Material(atlas, texture);
        return material.atlasLocation().equals(atlas) && material.texture().equals(texture);
    }
}
