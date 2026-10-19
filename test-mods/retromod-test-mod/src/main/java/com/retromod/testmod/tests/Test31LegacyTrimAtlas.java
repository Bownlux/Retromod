/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;

/**
 * #349: 26.3 removed the armor trim atlas that a custom armor layer fetches from the texture
 * manager and draws trims from. Retromod rebuilds it from 26.3's trim textures and palettes, so
 * the atlas is a real atlas again and the old sprite names resolve, including a {@code _darker}
 * palette. The entity glint render type 26.3 renamed links too. Runs on joining a world, once the
 * client has loaded its resources and can build textures.
 */
public final class Test31LegacyTrimAtlas implements Test {

    @Override
    public String description() {
        return "#349 armor trim atlas and sprites after 26.3 removed them";
    }

    @Override
    public TestResult run() {
        AbstractTexture texture = MinecraftClient.getInstance().getTextureManager()
                .getTexture(TexturedRenderLayers.ARMOR_TRIMS_ATLAS_TEXTURE);
        if (!(texture instanceof SpriteAtlasTexture atlas)) {
            return TestResult.fail("the armor trim atlas is " + texture);
        }
        Sprite missing = atlas.getSprite(MissingSprite.getMissingSpriteId());
        // 1.21.2 renamed the sprites; a host before it keeps the names this mod was built with.
        String[][] sprites = {
            {"trims/entity/humanoid/coast_iron", "trims/models/armor/coast_iron"},
            {"trims/entity/humanoid_leggings/coast_iron_darker", "trims/models/armor/coast_leggings_iron_darker"},
        };
        for (String[] names : sprites) {
            if (atlas.getSprite(new Identifier("minecraft", names[0])) == missing
                    && atlas.getSprite(new Identifier("minecraft", names[1])) == missing) {
                return TestResult.fail("the atlas has no sprite " + names[0]);
            }
        }
        return RenderLayer.getEntityGlint() != null
                ? TestResult.success() : TestResult.fail("the entity glint render type is null");
    }
}
