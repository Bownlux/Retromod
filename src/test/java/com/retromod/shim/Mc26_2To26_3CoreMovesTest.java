/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.Mc26_2To26_3CoreMoves;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minecraft 26.3 repackaged its rendering library and renamed a handful of vanilla classes. A class
 * move is only correct when the old name really is gone and the new one really exists, so these
 * tests check the shipped table against the 26.2 and 26.3 jars rather than trusting the harvester.
 */
class Mc26_2To26_3CoreMovesTest {

    @AfterEach
    void clearRedirects() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    private static Map<String, String> moves() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Mc26_2To26_3CoreMoves.register(t);
        return t.getClassRedirects();
    }

    @Test
    @DisplayName("The rendering repackage and the vanilla renames are both registered")
    void tableCoversBothHalvesOfTheJump() {
        Map<String, String> moves = moves();

        assertEquals("com/mojang/renderpearl/api/GpuFormat",
                moves.get("com/mojang/blaze3d/GpuFormat"),
                "the rendering library moved to renderpearl");
        assertEquals("net/minecraft/world/entity/monster/Enderman",
                moves.get("net/minecraft/world/entity/monster/EnderMan"),
                "26.3 corrected this capitalization, and mods reference it constantly");
        assertEquals("net/minecraft/world/level/block/RedstoneWireBlock",
                moves.get("net/minecraft/world/level/block/RedStoneWireBlock"));

        long renderpearl = moves.values().stream()
                .filter(v -> v.startsWith("com/mojang/renderpearl/")).count();
        assertTrue(renderpearl >= 150,
                "the repackage is the bulk of the jump, saw " + renderpearl);
    }

    @Test
    @DisplayName("No move collides with a class 26.3 still ships under its old name")
    void noMoveHijacksALiveClass() throws Exception {
        Path host = hostJar("26.3");
        Assumptions.assumeTrue(host != null, "26.3 jar present");
        Set<String> present = classNames(host);

        for (Map.Entry<String, String> move : moves().entrySet()) {
            assertFalse(present.contains(move.getKey()),
                    "26.3 still declares " + move.getKey()
                            + ", so redirecting it would hijack a live class");
            assertTrue(present.contains(move.getValue()),
                    "26.3 does not declare " + move.getValue() + ", so the move points nowhere");
        }
    }

    @Test
    @DisplayName("Every move starts from a class 26.2 actually had")
    void everyMoveStartsFromARealOldClass() throws Exception {
        Path host = hostJar("26.2");
        Assumptions.assumeTrue(host != null, "26.2 jar present");
        Set<String> present = classNames(host);

        for (String old : moves().keySet()) {
            assertTrue(present.contains(old),
                    old + " is not in 26.2, so nothing would ever match this move");
        }
    }

    private static Path hostJar(String version) {
        String home = System.getProperty("user.home", "");
        for (Path candidate : java.util.List.of(
                Path.of(home, "Library/Application Support/PrismLauncher/libraries",
                        "com/mojang/minecraft", version, "minecraft-" + version + "-client.jar"),
                Path.of("test-jars-mixin/minecraft-" + version + "-client.jar"))) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static Set<String> classNames(Path jar) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(n -> n.endsWith(".class"))
                    .forEach(n -> names.add(n.substring(0, n.length() - 6)));
        }
        return names;
    }
}
