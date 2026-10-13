/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import com.retromod.shim.forge.embedded.LegacyEnchantmentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The legacy enchantment pack: Spartan Shields' spikes, written from the old methods. */
class LegacyEnchantmentSupportTest {

    /** Shaped like the generated class after a mod's overrides; read by method and field name. */
    public static class Spikes {
        public final LegacyEnchantmentRarity rarity = LegacyEnchantmentRarity.UNCOMMON;
        public final LegacyEnchantmentType category = LegacyEnchantmentType.BREAKABLE;
        public final Object[] slots = new Object[0];
        public String id;
        protected String descriptionId = "enchantment.spikes";

        public int getMaxLevel() { return 3; }
        public int getMinCost(int level) { return 10 * (level * 2 - 1); }
        public int getMaxCost(int level) { return getMinCost(level) + 5; }
        public LegacyEnchantmentRarity getRarity() { return rarity; }
        public String getDescriptionId() { return descriptionId; }
        public boolean isCurse() { return false; }
        public boolean isTreasureOnly() { return false; }
        public boolean isDiscoverable() { return true; }
        public boolean isTradeable() { return true; }
    }

    @AfterEach
    void reset() {
        LegacyEnchantmentSupport.resetForTests();
    }

    @Test
    @DisplayName("an enchantment becomes its data file and joins the enchanting table and loot tags")
    void enchantmentBecomesData(@TempDir Path root) throws Exception {
        LegacyEnchantmentSupport.resetForTests();
        Spikes spikes = new Spikes();
        LegacyEnchantmentSupport.recordForTests("spartanshields:spikes", spikes);

        assertEquals(1, LegacyEnchantmentSupport.writePack(root, 48));

        assertEquals("spartanshields:spikes", spikes.id, "registration names the object for level lookups");
        String json = Files.readString(root.resolve("data/spartanshields/enchantment/spikes.json"));
        assertEquals("{\"description\":{\"translate\":\"enchantment.spikes\"},"
                + "\"supported_items\":\"#minecraft:enchantable/durability\",\"weight\":5,\"max_level\":3,"
                + "\"min_cost\":{\"base\":10,\"per_level_above_first\":20},"
                + "\"max_cost\":{\"base\":15,\"per_level_above_first\":20},\"anvil_cost\":2,"
                + "\"slots\":[\"any\"],\"effects\":{}}", json);
        assertTrue(Files.readString(root.resolve("data/minecraft/tags/enchantment/in_enchanting_table.json"))
                .contains("\"id\":\"spartanshields:spikes\",\"required\":false"));
        assertFalse(Files.exists(root.resolve("data/minecraft/tags/enchantment/curse.json")));
    }

    @Test
    @DisplayName("a registry key prints as its id")
    void keyId() {
        assertEquals("minecraft:silk_touch",
                LegacyEnchantmentSupport.keyId("ResourceKey[minecraft:enchantment / minecraft:silk_touch]"));
    }
}
