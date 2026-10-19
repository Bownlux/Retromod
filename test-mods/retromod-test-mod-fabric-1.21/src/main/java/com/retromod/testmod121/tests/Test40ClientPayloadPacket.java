/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** The client's {@code createC2SPacket(CustomPayload)}, which 26.1 renamed. Runs on a client only. */
public final class Test40ClientPayloadPacket implements Test {

    @Override
    public String description() {
        return "createC2SPacket builds a packet from a 1.21 payload";
    }

    @Override
    public TestResult run() {
        return ClientPlayNetworking.createC2SPacket(new Ping121(10)) != null
                ? TestResult.success()
                : TestResult.fail("createC2SPacket returned null");
    }
}
