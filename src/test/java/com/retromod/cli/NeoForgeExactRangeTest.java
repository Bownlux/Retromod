/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("a set of exact Minecraft ranges opens from its lowest version")
    void rangeSetOpensFromItsLowestVersion() {
        // Distant Horizons 3.3.3 declares [1.21],[1.21.1]; the old rewrite wrote "[1.21],)", which
        // NeoForge refuses with "Only fully-qualified sets allowed in multiple set scenario".
        String toml = """
                [[dependencies.distanthorizons]]
                    modId = "minecraft"
                    type = "required"
                    versionRange = "[1.21],[1.21.1]" # comment
                [[dependencies.distanthorizons]]
                    modId = "neoforge"
                    type = "required"
                    versionRange = "[*,)"
                """;
        String patched = com.retromod.core.ForgeModTransformer.relaxDependencies(toml);

        assertTrue(patched.contains("versionRange = \"[1.21,)\" # comment"), patched);
        assertTrue(patched.contains("versionRange = \"[*,)\""), "an open range stays as written: " + patched);
    }

    @Test
    @DisplayName("every Minecraft range shape opens above")
    void everyRangeShapeOpensAbove() {
        assertEquals("[1.20.1,)", relaxedMinecraftRange("[1.20.1,1.21)"));
        assertEquals("[1.21.1,)", relaxedMinecraftRange("[1.21.1]"));
        assertEquals("[1.21.8,)", relaxedMinecraftRange("1.21.8"));
        assertEquals("[1.20,)", relaxedMinecraftRange("[1.21,1.21.2),[1.20, 1.20.6]"));
        assertEquals("(1.20,)", relaxedMinecraftRange("(1.20,1.21]"));
        assertEquals("[0,)", relaxedMinecraftRange("(,1.21.1]"));
        assertEquals("[1.20,1.20.2),[1.21,)", relaxedMinecraftRange("[1.20,1.20.2),[1.21,)"),
                "a set that is already open above keeps its shape");
        assertEquals("*", relaxedMinecraftRange("*"));
        assertEquals("[21.0.143,)", relaxedMinecraftRange("[21.0.143,21.1),[21.1.1,21.2),"),
                "a trailing comma after the last range is ignored, as in Better Clouds' NeoForge range");
    }

    private static String relaxedMinecraftRange(String range) {
        String toml = "[[dependencies.example]]\nmodId=\"minecraft\"\ntype=\"required\"\nversionRange=\""
                + range + "\"\n";
        String patched = com.retromod.core.ForgeModTransformer.relaxDependencies(toml);
        java.util.regex.Matcher value = java.util.regex.Pattern.compile("versionRange=\"([^\"]*)\"").matcher(patched);
        assertTrue(value.find(), patched);
        return value.group(1);
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

    @Test
    @DisplayName("an incompatible range with a lower bound is kept by the shared relaxation")
    void lowerBoundedIncompatibleRangeIsKept() {
        String toml = """
                [[dependencies.supplementaries]]
                modId="farmersdelight"
                type="incompatible"
                versionRange="[0,1.3.0)"
                """;
        String patched = com.retromod.core.ForgeModTransformer.relaxDependencies(toml);

        assertTrue(patched.contains("versionRange=\"[0,1.3.0)\""),
                "[0,) would refuse every Farmer's Delight: " + patched);
    }

    @Test
    @DisplayName("an old mandatory-only dependency becomes optional with exactly one type line")
    void untypedMandatoryDependencyGetsOneType() {
        String toml = """
                [[dependencies.supplementaries]]
                modId="moonlight"
                mandatory=true
                versionRange="[1.21-2.17.4,)"
                """;
        String patched = com.retromod.core.ForgeModTransformer.relaxDependencies(toml);

        assertTrue(patched.contains("mandatory=false"), patched);
        assertEquals(1, patched.split("type\\s*=", -1).length - 1,
                "NeoForge reads only type, and one line is enough: " + patched);
        assertTrue(patched.contains("type=\"optional\""), patched);
        assertTrue(patched.contains("versionRange=\"[0,)\""), patched);
    }
}
