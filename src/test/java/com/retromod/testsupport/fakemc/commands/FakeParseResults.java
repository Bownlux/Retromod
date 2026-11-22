/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.commands;

/** Stands in for Brigadier's {@code ParseResults}: only the command text it came from. */
public final class FakeParseResults {
    public final String command;

    public FakeParseResults(String command) {
        this.command = command;
    }
}
