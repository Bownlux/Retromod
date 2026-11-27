/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.minecraft.resources.Identifier;

/** Fabric API's old {@code Renderer}: meshes come from 26.x's renderer, materials from Retromod's. */
public interface LegacyRenderer {
    MutableMesh meshBuilder();

    LegacyMaterialFinder materialFinder();

    LegacyRenderMaterial materialById(Identifier id);

    boolean registerMaterial(Identifier id, LegacyRenderMaterial material);
}
