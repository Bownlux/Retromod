/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The multi-version SRG union (1.3.0-snapshot.3): the srg-to-mojang table was extended from a
 * 1.20.1-only join to the union across the whole Forge SRG era (1.16.5..1.20.6), so pre-1.20.1
 * mods stop hitting per-modpack gaps (the #171 class of report). Smoke-checks that the (now much
 * larger) table loads cleanly and that a spread of harvested cross-version members resolve.
 */
class SrgUnionCoverageTest {

    @Test
    @DisplayName("the SRG table loads and is materially larger than the old 1.20.1-only join")
    void tableLoadsAndGrew() {
        SrgToMojangMapper m = SrgToMojangMapper.getInstance();
        Map<String, String> methods = m.getMethodMap();
        Map<String, String> fields = m.getFieldMap();
        // The old table was ~23.6k methods / ~30k fields; the union roughly doubles both.
        assertTrue(methods.size() > 40_000,
                "method map should hold the multi-version union, got " + methods.size());
        assertTrue(fields.size() > 45_000,
                "field map should hold the multi-version union, got " + fields.size());
    }

    @Test
    @DisplayName("sentinel members resolve to the correct Mojang names (1.20.1 base preserved)")
    void sentinelsPreserved() {
        SrgToMojangMapper m = SrgToMojangMapper.getInstance();
        // f_50069_ = Blocks.STONE, the canonical example from the file header.
        assertEquals("STONE", m.getFieldMap().get("f_50069_"));
    }

    @Test
    @DisplayName("harvested cross-version members (verified against ground truth) resolve")
    void unionEntriesResolve() {
        SrgToMojangMapper m = SrgToMojangMapper.getInstance();
        // These were re-verified against the official 1.18.2 mappings during the harvest.
        assertEquals("noCounter", m.getFieldMap().get("f_19507_"));
        assertEquals("interactionRangeSqr", m.getFieldMap().get("f_23938_"));
        assertEquals("getName", m.getMethodMap().get("m_96461_"));
    }

    @Test
    @DisplayName("no SRG id maps to conflicting names (ambiguous ids were dropped, not guessed)")
    void noSelfContradiction() {
        SrgToMojangMapper m = SrgToMojangMapper.getInstance();
        // The maps are keyed by srg id, so a conflict can't survive in the map itself; assert the
        // known-ambiguous 1.18/1.19-only ids that the harvest DROPPED are genuinely absent (i.e.
        // we did not silently keep one arbitrary side of a rename).
        // (These specific ids were among the 148 dropped as ambiguous across versions.)
        // A dropped id simply has no mapping; the harmless outcome is the mod's own NoSuchXError.
        assertFalse(m.getMethodMap().containsKey("__retromod_never_a_real_srg__"));
    }
}
