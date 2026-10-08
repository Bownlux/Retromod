/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The data pack half of the #264/#290 worldgen bridge: what a 1.19.2 mod registered in code has to
 * reappear as data pack files under the same ids, or its biome modifiers point at nothing.
 */
class LegacyWorldgenStoreTest {

    /** Codecs that write a marker per object, so the test sees exactly what was encoded. */
    private static final class FakeCodecs implements LegacyWorldgenStore.Codecs {
        @Override public String encodeConfigured(Object value) { return json("configured", value); }
        @Override public String encodePlaced(Object value) { return value == REJECTED ? null : json("placed", value); }
        @Override public String encodeBiome(Object value) { return json("biome", value); }
        @Override public int packFormat() { return 15; }
        @Override public Object repositorySource(String packId, Path root) { return null; }

        private static String json(String kind, Object value) {
            return "{\"kind\":\"" + kind + "\",\"value\":\"" + value + "\"}";
        }
    }

    private static final Object REJECTED = new Object() {
        @Override public String toString() { return "rejected"; }
    };

    @TempDir
    Path packRoot;

    @BeforeEach
    void setUp() {
        LegacyWorldgenStore.resetForTests();
    }

    @AfterEach
    void tearDown() {
        LegacyWorldgenStore.resetForTests();
    }

    @Test
    @DisplayName("recorded features are written under data/<namespace>/worldgen/<registry>")
    void writesRecordedFeatures() throws Exception {
        LegacyWorldgenStore.record(LegacyWorldgenStore.CONFIGURED, "pyrologernfriends:barn", "cf");
        LegacyWorldgenStore.record(LegacyWorldgenStore.PLACED, "pyrologernfriends:barn", "pf");

        int written = LegacyWorldgenStore.writePack(packRoot, new FakeCodecs());

        assertEquals(2, written);
        Path configured = packRoot.resolve("data/pyrologernfriends/worldgen/configured_feature/barn.json");
        Path placed = packRoot.resolve("data/pyrologernfriends/worldgen/placed_feature/barn.json");
        assertTrue(Files.isRegularFile(configured), "the biome modifier names this configured feature id");
        assertTrue(Files.readString(placed).contains("\"placed\""), "placed features use the placed codec");
        String meta = Files.readString(packRoot.resolve("pack.mcmeta"));
        assertTrue(meta.contains("\"pack_format\":15"), "the pack must declare the running game's format: " + meta);
    }

    @Test
    @DisplayName("an entry the codec rejects is skipped, the rest still written")
    void skipsRejectedEntries() throws Exception {
        LegacyWorldgenStore.record(LegacyWorldgenStore.PLACED, "modid:good", "pf");
        LegacyWorldgenStore.record(LegacyWorldgenStore.PLACED, "modid:bad", REJECTED);

        assertEquals(1, LegacyWorldgenStore.writePack(packRoot, new FakeCodecs()));
        assertFalse(Files.exists(packRoot.resolve("data/modid/worldgen/placed_feature/bad.json")));
        assertTrue(Files.exists(packRoot.resolve("data/modid/worldgen/placed_feature/good.json")));
    }

    @Test
    @DisplayName("a rewrite drops files from the previous run")
    void rewriteStartsClean() throws Exception {
        Path stale = packRoot.resolve("data/modid/worldgen/placed_feature/stale.json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "{}");
        LegacyWorldgenStore.record(LegacyWorldgenStore.PLACED, "modid:fresh", "pf");

        LegacyWorldgenStore.writePack(packRoot, new FakeCodecs());

        assertFalse(Files.exists(stale), "a feature the mod no longer registers must not linger in the pack");
    }

    @Test
    @DisplayName("ids that could climb out of the pack folder are refused")
    void refusesUnsafeIds() throws Exception {
        assertFalse(LegacyWorldgenStore.isSafeIdPart("../escape"));
        assertFalse(LegacyWorldgenStore.isSafeIdPart("a//b"));
        assertFalse(LegacyWorldgenStore.isSafeIdPart("Upper"));
        assertTrue(LegacyWorldgenStore.isSafeIdPart("trees/sakura_tree_1"));

        LegacyWorldgenStore.record(LegacyWorldgenStore.PLACED, "modid:../../escape", "pf");
        assertEquals(0, LegacyWorldgenStore.writePack(packRoot, new FakeCodecs()));
        assertFalse(Files.exists(packRoot.resolve("escape.json")));
    }

    @Test
    @DisplayName("the pack folder is named only from plain mod namespaces")
    void packIdUsesOnlyPlainNamespaces() {
        LegacyWorldgenStore.record(LegacyWorldgenStore.PLACED, "../../saves:escape", "pf");
        LegacyWorldgenStore.record(LegacyWorldgenStore.PLACED, "minecraft:patch", "pf");
        LegacyWorldgenStore.record(LegacyWorldgenStore.PLACED, "modid:tree", "pf");

        assertEquals("retromod_legacy_worldgen_modid", LegacyWorldgenStore.packId(),
                "the pack folder is deleted and rewritten, so a namespace must not reach outside it, "
                        + "and minecraft would pull vanilla biomes into a pack that overrides other packs");
    }

    @Test
    @DisplayName("re-registering an id keeps the latest object, as a registry would")
    void latestRegistrationWins() {
        LegacyWorldgenStore.record(LegacyWorldgenStore.CONFIGURED, "modid:tree", "first");
        LegacyWorldgenStore.record(LegacyWorldgenStore.CONFIGURED, "modid:tree", "second");
        assertEquals("second", LegacyWorldgenStore.entriesForTests()
                .get(LegacyWorldgenStore.CONFIGURED).get("modid:tree"));
    }
}
