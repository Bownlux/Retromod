/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pre-release host reports its own id, not the milestone it belongs to.
 *
 * <p>Found by running rc-2 on 26.3-rc-1: the client came up with
 * {@code IllegalArgumentException: Unsupported Minecraft pack target: 26.3-rc.1} while building the
 * resource manager, so pack transformation was off for the session. The window where this matters
 * is exactly the weeks before a release, which is when people test.
 */
class PackFormatPreReleaseTest {

    @Test
    @DisplayName("a release candidate resolves to the milestone it belongs to")
    void releaseCandidateResolves() {
        // Fabric normalises the launcher's 26.3-rc-1 to 26.3-rc.1, so both spellings show up.
        for (String spelling : new String[]{"26.3-rc.1", "26.3-rc-1", "26.3-pre-2", "26.3-pre.2"}) {
            assertEquals(PackFormat.resourceTarget("26.3"), PackFormat.resourceTarget(spelling),
                    spelling + " is 26.3, so it must get 26.3's resource pack format");
            assertEquals(PackFormat.dataTarget("26.3"), PackFormat.dataTarget(spelling),
                    spelling + " must get 26.3's data pack format too");
        }
    }

    @Test
    @DisplayName("an exact version is unchanged")
    void exactVersionStillWorks() {
        assertNotNull(PackFormat.resourceTarget("26.2"));
        assertNotNull(PackFormat.resourceTarget("1.21.11"));
    }

    @Test
    @DisplayName("a version from no known milestone still refuses rather than guessing")
    void unknownVersionStillThrows() {
        assertThrows(IllegalArgumentException.class, () -> PackFormat.resourceTarget("99.9"));
        assertThrows(IllegalArgumentException.class, () -> PackFormat.resourceTarget("not-a-version"));
    }
}
