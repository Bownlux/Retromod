/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;

/** A block model part made from an old model's quads, one list per side and one for unculled quads. */
final class LegacyQuadsPart implements BlockStateModelPart {
    private final List<BakedQuad>[] sides;
    private final List<BakedQuad> unculled;
    private final boolean ambientOcclusion;
    private final Material.Baked particle;
    private final int flags;
    private final boolean empty;

    LegacyQuadsPart(List<BakedQuad>[] sides, List<BakedQuad> unculled, boolean ambientOcclusion, Material.Baked particle) {
        this.sides = sides;
        this.unculled = unculled;
        this.ambientOcclusion = ambientOcclusion;
        this.particle = particle;
        // The part's flags are those of its quads, as 26.x works them out for its own quad lists.
        int found = 0;
        boolean none = true;
        for (List<BakedQuad> quads : sides) {
            found |= flagsOf(quads);
            none &= quads == null || quads.isEmpty();
        }
        this.flags = found | flagsOf(unculled);
        this.empty = none && (unculled == null || unculled.isEmpty());
    }

    /** Whether the old model gave no quads at all, so its flags say nothing about what it draws. */
    boolean empty() {
        return empty;
    }

    @Override
    public List<BakedQuad> getQuads(Direction side) {
        return side == null ? unculled : sides[side.ordinal()];
    }

    @Override
    public boolean useAmbientOcclusion() {
        return ambientOcclusion;
    }

    @Override
    public Material.Baked particleMaterial() {
        return particle;
    }

    @Override
    public int materialFlags() {
        return flags;
    }

    // A quad whose material cannot be read counts as translucent and animated, which the host accepts at some cost.
    private static int flagsOf(List<BakedQuad> quads) {
        int flags = 0;
        if (quads == null) return flags;
        try {
            for (BakedQuad quad : quads) flags |= quad.materialInfo().flags();
        } catch (RuntimeException | LinkageError unreadable) {
            return LegacyFabricModels.ALL_FLAGS;
        }
        return flags;
    }
}
