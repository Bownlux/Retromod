/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.retromod.testutil.RelocatedLibraryFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** A class read from the release jar names Retromod's relocated Gson and SLF4J; the copy a mod gets names the game's. */
class ShadedLibraryNamesTest {

    @Test
    @DisplayName("Relocated SLF4J and Gson become the game's everywhere the class names them, and nothing else changes")
    void relocatedLibrariesBecomeTheGames() {
        byte[] restored = ShadedLibraryNames.toHostNames(helper("x/Helper", SHADED_SLF4J, SHADED_GSON));

        assertFalse(namesShadedLibrary(restored), "no relocated name may stay, constant pool included");
        assertEquals(text(helper("x/Helper", HOST_SLF4J, HOST_GSON)), text(restored),
                "the copy must match the same helper written against the game's libraries");
        assertTrue(text(restored).contains(OTHER_RETROMOD), "a Retromod name that is not a library is kept");
    }

    @Test
    @DisplayName("A class without relocated Gson or SLF4J is returned as the same array")
    void otherClassesAreUnchanged() {
        byte[] plain = helper("x/Plain", HOST_SLF4J, HOST_GSON);

        assertSame(plain, ShadedLibraryNames.toHostNames(plain));
    }

    @Test
    @DisplayName("Relocated toml4j is left alone, since no game ships toml4j to rename it to")
    void tomlIsNotRenamed() {
        byte[] toml = tomlUser("x/Toml", SHADED_TOML);

        assertSame(toml, ShadedLibraryNames.toHostNames(toml));
    }
}
