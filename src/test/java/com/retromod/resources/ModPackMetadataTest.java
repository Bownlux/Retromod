/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.bytes;
import static com.retromod.resources.ResourceFixtures.text;
import static org.junit.jupiter.api.Assertions.*;

/** Forge 26.x refuses a mod jar's assets and data without a root pack.mcmeta, and then shuts its game bus down. */
class ModPackMetadataTest {

    private static MigrationContext context(String target, MigrationContext.Loader loader) {
        return MigrationContext.builder(target).sourceMcVersion("1.20.1").targetLoader(loader)
                .declaredNamespaces(List.of("oldmod")).build();
    }

    @Test
    @DisplayName("A Forge 26.2 jar without one gets the host's data format, as Forge's own jar declares it")
    void writesTheHostFormat() throws IOException {
        MapResourceTree tree = new MapResourceTree(Map.of("data/oldmod/recipe/gear.json", bytes("{}")));
        ModPackMetadata step = new ModPackMetadata();

        assertTrue(step.appliesTo(context("26.2", MigrationContext.Loader.FORGE)));
        assertEquals(1, step.apply(tree, context("26.2", MigrationContext.Loader.FORGE)));
        JsonObject pack = JsonParser.parseString(text(tree.read("pack.mcmeta"))).getAsJsonObject().getAsJsonObject("pack");
        assertEquals(107, pack.get("max_format").getAsInt());
        assertEquals("[107,1]", pack.get("min_format").toString());
    }

    @Test
    @DisplayName("A jar's own pack.mcmeta stays, a code-only jar still gets one, and NeoForge and older Forge are left alone")
    void leavesOthersAlone() throws IOException {
        MapResourceTree own = new MapResourceTree(Map.of("pack.mcmeta", bytes("{\"pack\":{\"pack_format\":15}}"),
                "assets/oldmod/lang/en_us.json", bytes("{}")));
        assertEquals(0, new ModPackMetadata().apply(own, context("26.2", MigrationContext.Loader.FORGE)));
        assertEquals("{\"pack\":{\"pack_format\":15}}", text(own.read("pack.mcmeta")));

        MapResourceTree codeOnly = new MapResourceTree(Map.of("oldmod/Library.class", new byte[]{1}));
        assertEquals(1, new ModPackMetadata().apply(codeOnly, context("26.2", MigrationContext.Loader.FORGE)),
                "Forge builds a pack for a library jar of code alone as well");

        assertFalse(new ModPackMetadata().appliesTo(context("26.3", MigrationContext.Loader.NEOFORGE)));
        assertFalse(new ModPackMetadata().appliesTo(context("1.20.1", MigrationContext.Loader.FORGE)));
    }
}
