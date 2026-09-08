/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.cli;

import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pre-release jar has to satisfy a {@code --target} that names its milestone.
 *
 * <p>The jar reports its own id, so a 26.3 pre-release says {@code 26.3-pre-2} while a user
 * reasonably types {@code --target 26.3}. Comparing the raw strings refused the one jar that was
 * exactly right, which is the jar anyone testing an unreleased version has. The registry already
 * knows every spelling a milestone answers to, so the check goes through it.
 */
class PreReleaseTargetAgreementTest {

    @Test
    @DisplayName("every pre-release spelling resolves to its milestone")
    void preReleaseSpellingsResolve() {
        for (String spelling : new String[]{
                "26.3-pre-1", "26.3-pre-2", "26.3-pre.2",
                "26.3 Pre-Release 2", "26.3-rc-1", "26.3-snapshot-10"}) {
            assertEquals("26.3", ShimRegistry.resolveVersion(spelling),
                    spelling + " must resolve to the 26.3 milestone, or a jar of that build cannot "
                    + "be used to target 26.3");
        }
    }

    @Test
    @DisplayName("a milestone and a released version still resolve to themselves")
    void milestonesPassThrough() {
        assertEquals("26.3", ShimRegistry.resolveVersion("26.3"));
        assertEquals("26.2", ShimRegistry.resolveVersion("26.2"));
        assertEquals("1.20.1", ShimRegistry.resolveVersion("1.20.1"));
    }

    @Test
    @DisplayName("an unrelated version does not resolve onto 26.3")
    void unrelatedVersionsAreNotAbsorbed() {
        for (String other : new String[]{"26.2", "26.4", "1.21.11"}) {
            assertNotEquals("26.3", ShimRegistry.resolveVersion(other),
                    other + " must not be treated as 26.3");
        }
    }
}
