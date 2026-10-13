/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** #328: a jar named "1.20.1 TwelvePlaytimeChapter4.jar" stopped NeoForge before the game started. */
class SyntheticEmbedderPackageNameTest {

    @Test
    void everyPackageSegmentIsAJavaIdentifier() {
        for (String jar : new String[] {"1.20.1 TwelvePlaytimeChapter4.jar", "2mod.jar", "plain-mod.jar"}) {
            String base = SyntheticEmbedder.embeddedBase(jar);
            for (String segment : base.split("/")) {
                assertTrue(segment.isEmpty() || isIdentifier(segment),
                        "segment '" + segment + "' of " + base + " is not a Java identifier");
            }
        }
    }

    private static boolean isIdentifier(String segment) {
        if (!Character.isJavaIdentifierStart(segment.charAt(0))) return false;
        return segment.chars().allMatch(Character::isJavaIdentifierPart);
    }
}
