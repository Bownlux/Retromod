/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.util.math.RotationAxis;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * #349: 26.3 widened {@code Axis.of} to take the {@code Vector3fc} interface and dropped the
 * {@code Vector3f} form this call was compiled against, the same JOML change 26.2 made to the GUI
 * entity preview. The call links only once Retromod widens its descriptor.
 */
public final class Test24JomlAxisWidening implements Test {

    @Override
    public String description() {
        return "#349 Axis.of(Vector3f) after the JOML widening";
    }

    @Override
    public TestResult run() {
        Quaternionf quarterTurn = RotationAxis.of(new Vector3f(0f, 1f, 0f)).rotationDegrees(90f);
        Vector3f turned = quarterTurn.transform(new Vector3f(1f, 0f, 0f));
        return Math.abs(turned.x) < 1.0e-4f && Math.abs(turned.z + 1f) < 1.0e-4f
                ? TestResult.success()
                : TestResult.fail("a quarter turn about +Y moved +X to " + turned);
    }
}
