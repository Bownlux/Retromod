/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/**
 * #344: 26.3 ships no GLFW, so a mod that polls GLFW itself failed with
 * {@code NoClassDefFoundError} on its first call. Hold My Items reads the GLFW clock in a
 * per-frame render hook, which closed the game before the title screen. Each poll here is one a
 * 1.20.1 client mod makes, with a literal key and button the way such a mod writes them.
 */
public final class Test23GlfwPolls implements Test {

    @Override
    public String description() {
        return "#344 GLFW polls on a host without GLFW";
    }

    @Override
    public TestResult run() {
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        double before = GLFW.glfwGetTime();
        double after = GLFW.glfwGetTime();
        if (before < 0.0 || after < before) {
            return TestResult.fail("GLFW clock went from " + before + " to " + after);
        }
        int key = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_R);
        int button = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        if (!isPressOrRelease(key) || !isPressOrRelease(button)) {
            return TestResult.fail("key state " + key + ", button state " + button);
        }
        double[] x = new double[1];
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(window, x, y);
        return Double.isFinite(x[0]) && Double.isFinite(y[0])
                ? TestResult.success()
                : TestResult.fail("cursor at " + x[0] + ", " + y[0]);
    }

    private static boolean isPressOrRelease(int state) {
        return state == GLFW.GLFW_PRESS || state == GLFW.GLFW_RELEASE;
    }
}
