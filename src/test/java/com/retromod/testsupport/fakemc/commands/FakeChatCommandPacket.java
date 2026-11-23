/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.commands;

/** Stands in for {@code ServerboundChatCommandPacket}, the unsigned command packet. */
public final class FakeChatCommandPacket implements FakePacket {
    private final String command;

    public FakeChatCommandPacket(String command) {
        this.command = command;
    }

    @Override
    public String toString() {
        return command;
    }
}
