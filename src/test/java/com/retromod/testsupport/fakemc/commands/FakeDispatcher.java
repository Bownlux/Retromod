/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.commands;

/** Stands in for Brigadier's {@code CommandDispatcher}. */
public final class FakeDispatcher {
    public FakeParseResults parse(String command, Object source) {
        return new FakeParseResults(command);
    }
}
