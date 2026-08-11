/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full AOT cache gives each mod a directory named after its mod id, which is read out of the
 * mod's own metadata. An id that climbs out of the cache would place both the written class files
 * and the ones read back anywhere the game can write.
 */
class FullAotModIdSafetyTest {

    @Test
    @DisplayName("An ordinary mod id is kept as-is")
    void keepsOrdinaryIds() {
        assertEquals("species", FullAotCompiler.safeModId("species"));
        assertEquals("yungsapi", FullAotCompiler.safeModId("yungsapi"));
        assertEquals("some_mod-1.2", FullAotCompiler.safeModId("some_mod-1.2"));
    }

    @Test
    @DisplayName("A mod id cannot escape the cache directory")
    void rejectsTraversal() {
        // The property that matters is where the id lands, not how it is spelled: separators are
        // removed, so a leftover ".." inside one segment is just an odd directory name.
        for (String hostile : new String[]{
                "../../../../evil", "../evil", "a/b/c", "a\\b", "..", ".", "/etc/passwd",
                "C:/windows/evil", "....//....//evil"}) {
            Path cache = Path.of("retromod-cache/full-aot");
            Path resolved = cache.resolve(FullAotCompiler.safeModId(hostile)).normalize();
            assertTrue(resolved.startsWith(cache),
                    "id " + hostile + " escaped to " + resolved);
            assertEquals(cache.getNameCount() + 1, resolved.getNameCount(),
                    "id " + hostile + " must stay a single directory name");
        }
    }

    @Test
    @DisplayName("Different hostile ids cannot collapse onto the same cache directory")
    void sanitizedIdsRetainDistinctDigests() {
        String slash = FullAotCompiler.safeModId("a/b");
        String question = FullAotCompiler.safeModId("a?b");

        assertNotEquals(slash, question);
        assertTrue(slash.startsWith("a_b_"));
        assertTrue(question.startsWith("a_b_"));
        assertEquals(slash, FullAotCompiler.safeModId("a/b"),
                "the collision suffix must be stable between runs");
    }

    @Test
    @DisplayName("An empty or unusable id still yields a usable directory name")
    void alwaysReturnsSomething() {
        assertEquals("unnamed-mod", FullAotCompiler.safeModId(null));
        assertEquals("unnamed-mod", FullAotCompiler.safeModId(""));
        assertEquals("unnamed-mod", FullAotCompiler.safeModId("."));
        assertEquals("unnamed-mod", FullAotCompiler.safeModId(".."));
        assertFalse(FullAotCompiler.safeModId("...").isBlank());
    }
}
