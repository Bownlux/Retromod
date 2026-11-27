/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import java.util.function.Function;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;

/** Fabric API 1.20.2 to 1.21.3's {@code ModelModifier.BeforeBake}: replaces a model just before it is baked. */
@FunctionalInterface
public interface LegacyBeforeBake {
    UnbakedModel modifyModelBeforeBake(UnbakedModel model, Context context);

    interface Context {
        Identifier resourceId();

        Identifier topLevelId();

        Function<SpriteId, TextureAtlasSprite> textureGetter();

        ModelState settings();

        ModelBaker baker();

        ModelBakery loader();
    }
}
