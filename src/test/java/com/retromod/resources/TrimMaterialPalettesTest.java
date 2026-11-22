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

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Old armor trim materials load on 26.3, which reads a palette id instead of an asset name. */
class TrimMaterialPalettesTest {

    private static MapResourceTree run(String source, String target, MapResourceTree tree) throws IOException {
        MigrationContext context = MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(List.of("bop")).build();
        new ModResourceMigrations(List.of(new TrimMaterialPalettes())).run(tree, context);
        return tree;
    }

    private static MapResourceTree bopTrims() throws IOException {
        return new MapResourceTree(entries(
                "data/bop/trim_material/rose_quartz.json", """
                        {"asset_name": "rose_quartz", "description": {"translate": "trim_material.bop.rose_quartz"},
                         "ingredient": "bop:rose_quartz_chunk", "item_model_index": 0.5}""",
                "data/bop/trim_material/iron_like.json", """
                        {"asset_name": "iron", "description": {"translate": "trim_material.bop.iron_like"}}""",
                "assets/bop/textures/trims/color_palettes/rose_quartz.png", "palette"));
    }

    @Test
    @DisplayName("A material with its own palette names it in its namespace, and the palette is copied to the 26.3 folder")
    void ownPaletteIsCopied() throws IOException {
        MapResourceTree tree = run("1.21.1", "26.3", bopTrims());
        JsonObject material = JsonParser.parseString(text(tree.entries().get("data/bop/trim_material/rose_quartz.json")))
                .getAsJsonObject();
        assertEquals("bop:trim/rose_quartz", material.get("palette_id").getAsString());
        assertEquals("palette", text(tree.entries().get("assets/bop/textures/palettes/trim/rose_quartz.png")));
        assertTrue(tree.exists("assets/bop/textures/trims/color_palettes/rose_quartz.png"), "the original stays");
    }

    @Test
    @DisplayName("A material that reuses a vanilla palette names vanilla's")
    void vanillaPaletteIsNamed() throws IOException {
        MapResourceTree tree = run("1.21.1", "26.3", bopTrims());
        assertEquals("minecraft:trim/iron", JsonParser.parseString(text(tree.entries().get("data/bop/trim_material/iron_like.json")))
                .getAsJsonObject().get("palette_id").getAsString());
    }

    @Test
    @DisplayName("A mod's override of a vanilla material gets its palette id, but no palette is written into minecraft")
    void minecraftNamespaceIsNeverWritten() throws IOException {
        MapResourceTree tree = run("1.21.1", "26.3", new MapResourceTree(entries(
                "data/minecraft/trim_material/gold.json", """
                        {"asset_name": "gold", "description": {"translate": "trim_material.minecraft.gold"}}""",
                "assets/minecraft/textures/trims/color_palettes/gold.png", "recolored")));
        assertEquals("minecraft:trim/gold", JsonParser.parseString(text(tree.entries().get("data/minecraft/trim_material/gold.json")))
                .getAsJsonObject().get("palette_id").getAsString());
        assertFalse(tree.exists("assets/minecraft/textures/palettes/trim/gold.png"), "vanilla's palette folder is left alone");
    }

    @Test
    @DisplayName("Hosts before 26.3 and mods built for 26.3 are left alone")
    void gatedBySourceAndTarget() throws IOException {
        assertFalse(text(run("1.21.1", "26.2", bopTrims()).entries().get("data/bop/trim_material/rose_quartz.json"))
                .contains("palette_id"));
        assertFalse(text(run("26.3", "26.3", bopTrims()).entries().get("data/bop/trim_material/rose_quartz.json"))
                .contains("palette_id"));
    }
}
