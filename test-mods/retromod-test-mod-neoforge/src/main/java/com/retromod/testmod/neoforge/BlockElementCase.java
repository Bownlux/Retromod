/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import java.util.Map;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockElementRotation;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

/** Block model elements a 1.21.1 model loader builds and reads, which 26.1 renamed and turned into records. */
final class BlockElementCase {

    private BlockElementCase() {}

    /** An element with one face and a rotation, built and read back field by field, as Sophisticated Backpacks reads them. */
    static String run() {
        BlockElementRotation turn = new BlockElementRotation(new Vector3f(8, 8, 8), Direction.Axis.Y, 22.5f, true);
        BlockElementFace face = new BlockElementFace(null, 2, "#all", new BlockFaceUV(new float[]{0, 0, 16, 8}, 90));
        BlockElement built = new BlockElement(new Vector3f(1, 2, 3), new Vector3f(4, 5, 6), Map.of(Direction.UP, face), turn, false);
        if (built.from.x() != 1 || built.to.z() != 6 || built.faces.size() != 1 || built.shade) {
            return "fail: the element read back from " + built.from + " to " + built.to + ", shade " + built.shade;
        }
        if (built.rotation.angle() != 22.5f || built.rotation.axis() != Direction.Axis.Y || built.rotation.origin().y() != 8) {
            return "fail: the rotation read back " + built.rotation;
        }
        BlockElementFace up = built.faces.get(Direction.UP);
        if (up.tintIndex() != 2 || !up.texture().equals("#all") || up.uv().rotation != 90 || up.uv().getU(0) != 0
                || up.uv().getU(2) != 16) {
            return "fail: the face read back tint " + up.tintIndex() + ", texture " + up.texture() + ", uv rotation " + up.uv().rotation;
        }
        return "success";
    }
}
