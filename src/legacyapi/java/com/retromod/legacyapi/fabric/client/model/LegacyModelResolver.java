/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;

/** Fabric API 1.20.2 to 1.21.3's {@code ModelResolver}: supplies a model for an id, before any model file. */
@FunctionalInterface
public interface LegacyModelResolver {
    UnbakedModel resolveModel(Context context);

    interface Context {
        Identifier id();

        UnbakedModel getOrLoadModel(Identifier id);

        ModelBakery loader();
    }
}
