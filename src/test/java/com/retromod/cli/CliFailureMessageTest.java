/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A failed mod's line names the error, since some exceptions carry no message. */
class CliFailureMessageTest {

    @Test
    @DisplayName("An exception without a message is named by its type, never printed as null")
    void namesTheExceptionType() {
        // JEI, spark and Kiwi failed as "failed: null", which said nothing about the cause.
        assertEquals("NullPointerException", RetromodCli.describeFailure(new NullPointerException()));
        assertEquals("IllegalStateException: no target", RetromodCli.describeFailure(new IllegalStateException("no target")));
        assertEquals("IllegalStateException", RetromodCli.describeFailure(new IllegalStateException(" ")));
    }
}
