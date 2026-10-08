/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The SRG drift table needs the mod's real Minecraft version. An earlier dependency range was
 * read instead, so {@code m_7366_} became {@code getDefenseForType} in a 1.18.2 mod (#311).
 */
class ForgeMinecraftDependencyVersionTest {

    @Test
    void readsMinecraftRangeEvenWhenForgeRangeComesFirst() {
        String geckoLib3 = """
                [[dependencies.geckolib3]]
                    modId="forge"
                    versionRange="[40.0,)"
                [[dependencies.geckolib3]]
                    modId="minecraft"
                    versionRange="[1.18, 1.19)"
                """;
        assertEquals("1.18", ForgeModTransformer.minecraftDependencyVersion(geckoLib3),
                "the forge range [40.0,) is not a Minecraft version");
    }

    @Test
    void skipsAnAddonRangeThatLooksLikeAMinecraftVersion() {
        String archipelago = """
                [[dependencies.archipelagoadditions]]
                modId="iobaddons"
                mandatory=false
                versionRange="[1.1d,)"
                [[dependencies.archipelagoadditions]] #optional
                    modId="forge" #mandatory
                    versionRange="[39,)" #mandatory
                [[dependencies.archipelagoadditions]]
                    modId="minecraft"
                    versionRange="[1.18.2,1.19)"
                """;
        assertEquals("1.18.2", ForgeModTransformer.minecraftDependencyVersion(archipelago),
                "iobaddons [1.1d,) must not be taken for Minecraft 1.1");
    }

    @Test
    void returnsNullWithoutAMinecraftDependency() {
        assertNull(ForgeModTransformer.minecraftDependencyVersion("""
                [[dependencies.example]]
                    modId="forge"
                    versionRange="[47,)"
                """), "the caller keeps its older fallback when no minecraft block exists");
    }
}
