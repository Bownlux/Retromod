/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.commands;

/** Stands in for {@code SignableCommand}: a {@code msg} command carries a signable message, others do not. */
public final class FakeSignableCommand {
    public static boolean hasSignableArguments(FakeParseResults parsed) {
        return parsed.command.startsWith("msg ");
    }
}
