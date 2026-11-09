/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;

/** Forge 1.20.1's IUnbakedGeometry: geometry an old geometry loader read, which bakes into an old BakedModel. */
public interface IUnbakedGeometry<T extends IUnbakedGeometry<T>> {

    BakedModel bake(LegacyGeometryBakingContext context, ModelBaker baker, Function<SpriteId, TextureAtlasSprite> spriteGetter,
            ModelState modelState, ItemOverrides overrides, Identifier modelLocation);

    /** Forge 1.20.1 asked the geometry for the models it names; Retromod resolves them when they bake. */
    default void resolveParents(Function<Identifier, UnbakedModel> modelGetter, LegacyGeometryBakingContext context) {}

    default Set<String> getConfigurableComponentNames() {
        return Set.of();
    }
}
