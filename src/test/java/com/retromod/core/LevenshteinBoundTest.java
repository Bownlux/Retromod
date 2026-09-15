/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fuzzy resolver asks whether two names are within an edit distance, never what the distance
 * is, so it uses a bounded check that skips most of the table. This holds that check against the
 * plain distance it replaced. They have to agree on every input, because disagreeing means a
 * resolved reference silently changes: a member that used to look like a rename stops scoring as
 * one, or one that did not starts to.
 */
class LevenshteinBoundTest {

    private static void agree(String a, String b, int max) {
        boolean expected = FuzzyMethodResolver.levenshteinDistance(a, b) <= max;
        assertEquals(expected, FuzzyMethodResolver.levenshteinAtMost(a, b, max),
                () -> "disagreed on (\"" + a + "\", \"" + b + "\", max=" + max + "); distance is "
                        + FuzzyMethodResolver.levenshteinDistance(a, b));
    }

    @Test
    @DisplayName("the bounded check matches the distance on the shapes the resolver sees")
    void matchesOnRealMemberNames() {
        String[] names = {
            "", "a", "getWorld", "getEntityWorld", "getDist", "isProduction", "getGamePath",
            "getGameDir", "versionInfo", "getVersionInfo", "renderBackground", "extractBackground",
            "pushPose", "popPose", "getSlots", "getSlotCount", "m_142425_", "setRecipeUsed",
            "fillStackedContents", "java/lang/Object", "net/minecraft/world/item/ItemStack",
            "net/minecraft/world/item/ItemInstance",
        };
        for (String a : names) {
            for (String b : names) {
                for (int max = 0; max <= 12; max++) {
                    agree(a, b, max);
                }
            }
        }
    }

    @Test
    @DisplayName("the bounded check matches the distance on random strings")
    void matchesOnRandomStrings() {
        Random random = new Random(20260908L); // fixed, so a failure is reproducible
        for (int trial = 0; trial < 4000; trial++) {
            String a = randomName(random, random.nextInt(14));
            String b = randomName(random, random.nextInt(14));
            agree(a, b, random.nextInt(6));
        }
    }

    @Test
    @DisplayName("a negative bound accepts nothing, including two equal strings")
    void negativeBoundAcceptsNothing() {
        assertFalse(FuzzyMethodResolver.levenshteinAtMost("same", "same", -1));
        assertFalse(FuzzyMethodResolver.levenshteinAtMost("a", "b", -1));
    }

    @Test
    @DisplayName("a long shared prefix does not hide a difference past the bound")
    void longCommonPrefixStillCounts() {
        String base = "getBlockEntityRenderDispatcher";
        assertTrue(FuzzyMethodResolver.levenshteinAtMost(base, base + "s", 1));
        assertFalse(FuzzyMethodResolver.levenshteinAtMost(base, base + "Extra", 3));
        assertFalse(FuzzyMethodResolver.levenshteinAtMost(base, "renderBlockEntity", 3));
    }

    private static String randomName(Random random, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(4))); // a small alphabet makes near-misses common
        }
        return sb.toString();
    }
}
