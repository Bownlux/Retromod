/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.MinecraftClient;

/** Regression for the full intermediary mapping and Window handle shim chain. */
public final class Test13WindowHandleShim implements Test {

    @Override
    public String description() {
        return "window handle mapping and shim chain";
    }

    @Override
    public TestResult run() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return TestResult.fail("MinecraftClient instance was null");
        }

        long handle = client.getWindow().getHandle();
        return handle != 0L
                ? TestResult.success()
                : TestResult.fail("GLFW window handle was zero");
    }
}
