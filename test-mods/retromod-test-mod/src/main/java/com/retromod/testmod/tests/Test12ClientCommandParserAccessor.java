/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.network.ClientPlayNetworkHandler;

/** Load-time regression for the synthetic command parser accessor bridge. */
public class Test12ClientCommandParserAccessor implements Test {

    @Override
    public String description() {
        return "client command parser accessor bridge";
    }

    @Override
    public TestResult run() {
        try {
            Class<?> packetListener = ClientPlayNetworkHandler.class;
            return packetListener != null
                    ? TestResult.success()
                    : TestResult.fail("ClientPlayNetworkHandler.class returned null");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
