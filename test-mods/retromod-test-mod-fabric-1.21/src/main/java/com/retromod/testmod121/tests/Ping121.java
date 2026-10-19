/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** A 1.20.5-style custom payload, which 26.x knows as a CustomPacketPayload. */
record Ping121(int value) implements CustomPayload {

    static final CustomPayload.Id<Ping121> ID = new CustomPayload.Id<>(Identifier.of("retromod_test", "n1_payload"));

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
