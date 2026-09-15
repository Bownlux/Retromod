/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A nested class cannot move somewhere its outer class does not.
 *
 * <p>{@code GameRules$VisitorCaller} was mapped into {@code gamerules/GameRules} while
 * {@code GameRules} itself had no row, so a mod reading a game rule died with
 * {@code NoClassDefFoundError: net/minecraft/world/level/GameRules}. The comment above that block
 * said the row was in this file and it was not; git history shows it had been there and was lost.
 * The test mod caught it in game, which is a slow way to find a one-line deletion.
 *
 * <p>This holds only the cases where the destination inner clearly names its own outer. Where a
 * class was split and its inners went to different places, deciding the outer's destination needs
 * the member comparison the audit does, not a rule, so those are listed as known and skipped.
 */
class OuterClassMoveCoverageTest {

    private static final String TABLE = "/mojang-class-moves-26.1.tsv";

    private static final String BASELINE = "/outer-class-move-baseline.txt";

    private static Map<String, String> rows() {
        Map<String, String> rows = new LinkedHashMap<>();
        try (InputStream in = OuterClassMoveCoverageTest.class.getResourceAsStream(TABLE)) {
            assertNotNull(in, TABLE + " must ship on the classpath");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length >= 2) rows.put(parts[0], parts[1]);
            }
        } catch (Exception e) {
            fail("could not read " + TABLE + ": " + e);
        }
        return rows;
    }

    private static TreeSet<String> orphans() {
        Map<String, String> rows = rows();
        TreeSet<String> found = new TreeSet<>();
        rows.forEach((source, destination) -> {
            int nest = source.indexOf('$');
            if (nest < 0) return;
            String outer = source.substring(0, nest);
            if (rows.containsKey(outer)) return;
            int destNest = destination.indexOf('$');
            if (destNest < 0) return;
            String destinationOuter = destination.substring(0, destNest);
            if (destinationOuter.equals(outer)) return; // inner renamed in place
            found.add(outer + "\t" + destinationOuter);
        });
        return found;
    }

    private static TreeSet<String> baseline() {
        TreeSet<String> expected = new TreeSet<>();
        try (InputStream in = OuterClassMoveCoverageTest.class.getResourceAsStream(BASELINE)) {
            assertNotNull(in, BASELINE + " must ship on the test classpath");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.strip();
                if (!entry.isEmpty() && !entry.startsWith("#")) expected.add(entry);
            }
        } catch (Exception e) {
            fail("could not read " + BASELINE + ": " + e);
        }
        return expected;
    }

    @Test
    @DisplayName("no new inner move leaves its outer unmapped")
    void noNewOrphanedOuter() {
        TreeSet<String> added = new TreeSet<>(orphans());
        added.removeAll(baseline());
        assertTrue(added.isEmpty(),
                "an inner class now moves into a different outer while that outer has no row. "
                + "Check the host jars: if the outer is gone too, a mod naming it gets "
                + "NoClassDefFoundError and needs its own row. If the outer survives, add it to "
                + "the baseline: " + added);
    }

    @Test
    @DisplayName("a resolved orphan leaves the baseline")
    void baselineDoesNotRot() {
        TreeSet<String> stale = baseline();
        stale.removeAll(orphans());
        assertTrue(stale.isEmpty(),
                "these no longer orphan an outer, so the baseline should shrink with them: " + stale);
    }

    @Test
    @DisplayName("GameRules keeps the row whose loss the test mod caught in game")
    void gameRulesRowIsPresent() {
        assertEquals("net/minecraft/world/level/gamerules/GameRules",
                rows().get("net/minecraft/world/level/GameRules"),
                "GameRules moved into its own package at 26.1 and is one of the most referenced "
                + "classes in modded code");
    }
}
