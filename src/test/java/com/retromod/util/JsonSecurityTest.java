/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSecurityTest {

    @Test
    void structuralDepthIgnoresStringsAndComments() {
        String json = "{\"text\":\"[[[{{{\",/* [[[ */\"value\":[1]}";

        assertDoesNotThrow(() -> JsonSecurity.validate(json, 1_024, 2, "fixture"));
    }

    @Test
    void excessiveStructuralDepthIsRejected() {
        String json = "[".repeat(257) + "0" + "]".repeat(257);

        IOException failure = assertThrows(IOException.class,
                () -> JsonSecurity.validate(json, 1_024, 256, "fixture"));

        assertTrue(failure.getMessage().contains("256 levels"), failure.getMessage());
    }

    @Test
    void byteLimitUsesUtf8LengthInsteadOfCharacterCount() {
        String json = "\"éé\"";

        assertThrows(IOException.class,
                () -> JsonSecurity.validate(json, 5, 4, "fixture"));
        assertDoesNotThrow(() -> JsonSecurity.validate(
                json.getBytes(StandardCharsets.UTF_8), 6, 4, "fixture"));
    }

    @Test
    void malformedUtf8IsRejectedBeforeParsing() {
        byte[] invalid = {(byte) 0xc3, 0x28};

        IOException failure = assertThrows(IOException.class,
                () -> JsonSecurity.validate(invalid, 16, 4, "fixture"));

        assertTrue(failure.getMessage().contains("not valid UTF-8"), failure.getMessage());
    }
}
