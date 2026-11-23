/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.commands;

import java.util.ArrayList;
import java.util.List;

/** Stands in for {@code ClientPacketListener}: keeps every packet it is asked to send in {@code SENT}. */
public final class FakePacketListener {
    public static final List<Object> SENT = new ArrayList<>();
    private final FakeDispatcher commands = new FakeDispatcher();
    private final FakeSuggestionProvider suggestions = new FakeSuggestionProvider();

    public FakeDispatcher getCommands() {
        return commands;
    }

    public FakeSuggestionProvider getSuggestionsProvider() {
        return suggestions;
    }

    public void send(FakePacket packet) {
        SENT.add(packet);
    }
}
