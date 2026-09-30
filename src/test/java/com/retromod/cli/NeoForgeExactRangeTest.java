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
    @Test
    @DisplayName("#273: a sibling mod's range and a bounded loaderVersion are relaxed")
    void siblingRangeAndLoaderVersionAreRelaxed() throws Exception {
        String toml = """
                modLoader="javafml"
                loaderVersion="[47,48)"
                [[dependencies.refinedstoragedelight]]
                modId="farmersdelight"
                type="required"
                versionRange="[1.20.1-1.2.4,)"
                [[dependencies.refinedstoragedelight]]
                modId="minecraft"
                type="required"
                versionRange="[1.20.1,1.21)"
                """;
        Method relax = RetromodCli.class.getDeclaredMethod(
                "relaxNeoForgeDependencies", byte[].class);
        relax.setAccessible(true);
        String patched = new String((byte[]) relax.invoke(null,
                (Object) toml.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);

        // Farmer's Delight 1.3.4 sorts below "1.20.1-1.2.4", and optional deps are still checked.
        assertTrue(patched.contains("versionRange=\"[0,)\""), patched);
        assertTrue(patched.contains("loaderVersion=\"[1,)\""), patched);
        assertTrue(patched.contains("versionRange=\"[1.20.1,)\""),
                "the minecraft floor is kept: " + patched);
    }

    @Test
    @DisplayName("an incompatible dependency keeps the range of versions it rejects")
    void incompatibleRangeIsKept() throws Exception {
        String toml = """
                [[dependencies.mymod]]
                modId="embeddium"
                versionRange="[,0.3.20)"
                type="incompatible"
                """;
        Method relax = RetromodCli.class.getDeclaredMethod(
                "relaxNeoForgeDependencies", byte[].class);
        relax.setAccessible(true);
        String patched = new String((byte[]) relax.invoke(null,
                (Object) toml.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);

        // "[0,)" here would declare every Embeddium incompatible, not just the old ones.
        assertTrue(patched.contains("versionRange=\"[,0.3.20)\""), patched);
    }
}
