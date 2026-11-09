/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.mojang.math.Transformation;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.RenderTypeGroup;

/** Forge 1.20.1's IGeometryBakingContext, with the texture lookups Forge 26.2's context no longer has. */
public interface LegacyGeometryBakingContext {

    String getModelName();

    boolean hasMaterial(String name);

    SpriteId getMaterial(String name);

    boolean isGui3d();

    boolean useBlockLight();

    boolean useAmbientOcclusion();

    ItemTransforms getTransforms();

    Transformation getRootTransform();

    Identifier getRenderTypeHint();

    default Identifier getRenderTypeFastHint() {
        return null;
    }

    boolean isComponentVisible(String component, boolean fallback);

    /** The named render type group, as Forge 1.20.1 looked it up by name. */
    default RenderTypeGroup getRenderType(Identifier name) {
        return LegacyGeometryContext.namedRenderType(name);
    }
}
