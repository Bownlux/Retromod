/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModrinthVersionCheckerSecurityTest {

    @Test
    void allowsOnlyTheFixedHttpsApiOrigin() {
        assertTrue(ModrinthVersionChecker.isAllowedApiUri(
                URI.create("https://api.modrinth.com/v2/project/example")));
        assertFalse(ModrinthVersionChecker.isAllowedApiUri(
                URI.create("http://api.modrinth.com/v2/project/example")));
        assertFalse(ModrinthVersionChecker.isAllowedApiUri(
                URI.create("https://api.modrinth.com.evil.invalid/v2/project/example")));
        assertFalse(ModrinthVersionChecker.isAllowedApiUri(
                URI.create("https://127.0.0.1/v2/project/example")));
        assertFalse(ModrinthVersionChecker.isAllowedApiUri(
                URI.create("https://api.modrinth.com:8443/v2/project/example")));
    }
}
