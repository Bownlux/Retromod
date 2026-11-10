/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.block.model;

import com.mojang.math.Quadrant;
import net.minecraft.client.resources.model.cuboid.CuboidFace;

/** Minecraft's old face UVs, which 1.21.5 folded into the face itself, rebuilt for old model code. */
public class BlockFaceUV {

    // Both were public: old code reads the corners and the rotation in degrees, and fills in missing corners.
    public float[] uvs;
    public final int rotation;

    public BlockFaceUV(float[] uvs, int rotation) {
        this.uvs = uvs;
        this.rotation = rotation;
    }

    /** The U coordinate, from 0 to 16, a vertex takes, counted the way Minecraft's own face baking counts it. */
    public float getU(int vertex) {
        return hostUvs().getVertexU(shifted(vertex));
    }

    /** The V coordinate, from 0 to 16, a vertex takes. */
    public float getV(int vertex) {
        return hostUvs().getVertexV(shifted(vertex));
    }

    /** The vertex that lands on {@code vertex} once the rotation is undone. */
    public int getReverseIndex(int vertex) {
        return Math.floorMod(vertex - quarterTurns(), 4);
    }

    /** Takes these corners when the face named none of its own. */
    public void setMissingUv(float[] uvs) {
        if (this.uvs == null) this.uvs = uvs;
    }

    /** The corners as Minecraft's face keeps them, or null when the face takes them from its element's bounds. */
    CuboidFace.UVs hostUvs() {
        return uvs == null ? null : new CuboidFace.UVs(uvs[0], uvs[1], uvs[2], uvs[3]);
    }

    /** The rotation as Minecraft's face keeps it. */
    Quadrant quadrant() {
        return Quadrant.values()[quarterTurns()];
    }

    // The corner a vertex reads once the face is turned.
    private int shifted(int vertex) {
        return Math.floorMod(vertex + quarterTurns(), 4);
    }

    private int quarterTurns() {
        return Math.floorMod(rotation / 90, 4);
    }

    /** The old UVs of one of Minecraft's faces. */
    static BlockFaceUV of(CuboidFace face) {
        CuboidFace.UVs host = face.uvs();
        float[] corners = host == null ? null : new float[]{host.minU(), host.minV(), host.maxU(), host.maxV()};
        return new BlockFaceUV(corners, face.rotation().ordinal() * 90);
    }
}
