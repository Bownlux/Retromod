/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.util.Map;
import net.minecraft.client.render.model.json.ModelElement;
import net.minecraft.client.render.model.json.ModelElementFace;
import net.minecraft.client.render.model.json.ModelElementTexture;
import net.minecraft.client.render.model.json.ModelRotation;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;

/** Block model elements an old model loader builds and reads, which 26.1 renamed and turned into records. */
public final class Test83OldBlockElements implements Test {

    @Override
    public String description() {
        return "An old block model element, face, UVs and rotation build and read back field by field";
    }

    @Override
    public TestResult run() {
        ModelRotation turn = new ModelRotation(new Vector3f(8, 8, 8), Direction.Axis.Y, 22.5f, true);
        ModelElementFace face = new ModelElementFace(null, 2, "#all", new ModelElementTexture(new float[]{0, 0, 16, 8}, 90));
        ModelElement built = new ModelElement(new Vector3f(1, 2, 3), new Vector3f(4, 5, 6), Map.of(Direction.UP, face), turn, false);
        if (built.from.x() != 1 || built.to.z() != 6 || built.faces.size() != 1 || built.shade) {
            return TestResult.fail("the element read back from " + built.from + " to " + built.to + ", shade " + built.shade);
        }
        if (built.rotation.angle() != 22.5f || built.rotation.axis() != Direction.Axis.Y || built.rotation.origin().y() != 8) {
            return TestResult.fail("the rotation read back " + built.rotation);
        }
        ModelElementFace up = built.faces.get(Direction.UP);
        if (up.tintIndex != 2 || !up.textureId.equals("#all") || up.textureData.rotation != 90
                || up.textureData.getU(0) != 0 || up.textureData.getU(2) != 16) {
            return TestResult.fail("the face read back tint " + up.tintIndex + ", texture " + up.textureId
                    + ", uv rotation " + up.textureData.rotation);
        }
        return TestResult.success();
    }
}
