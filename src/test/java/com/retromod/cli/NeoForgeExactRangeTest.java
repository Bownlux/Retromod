/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeExactRangeTest {

    @Test
    @DisplayName("#188: an exact NeoForge Minecraft range is widened for a newer host")
    void exactMinecraftRangeIsWidened() throws Exception {
        String toml = """
                [[dependencies.caelum]]
                modId="minecraft"
                type="required"
                versionRange="[1.21.1]"
                """;
        Method relax = RetromodCli.class.getDeclaredMethod(
                "relaxNeoForgeDependencies", byte[].class);
        relax.setAccessible(true);
        byte[] output = (byte[]) relax.invoke(null,
                (Object) toml.getBytes(StandardCharsets.UTF_8));
        String patched = new String(output, StandardCharsets.UTF_8);

        assertTrue(patched.contains("versionRange=\"[1.21.1,)\""), patched);
    }
}
