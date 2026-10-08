/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #264 and #277: Forge kept {@code m_7366_} while the member behind it changed. In a 1.19.2 mod it
 * is {@code ArmorMaterial.getDurabilityForSlot}; from 1.19.4 it is {@code getDefenseForType}. The
 * one-name SRG table renamed a 1.19.2 mod's durability method to the defense name, and its armor
 * material came out half bridged.
 */
class SrgVersionDriftTest {

    private static final String MATERIAL = "net/minecraft/world/item/ArmorMaterial";
    private static final String SLOT_DESC = "(Lnet/minecraft/world/entity/EquipmentSlot;)I";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        SrgToMojangMapper.getInstance().applyTo(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a 1.19.2 mod's m_7366_ is getDurabilityForSlot")
    void oldModGetsItsOwnVersionsName() {
        transformer.setSrgSourceVersion("1.19.2");
        assertEquals("getDurabilityForSlot",
                transformer.remapQualifiedMethodName(MATERIAL, "m_7366_", SLOT_DESC));
    }

    @Test
    @DisplayName("a 1.20.1 mod's m_7366_ is getDefenseForType")
    void newerModKeepsTheCurrentName() {
        transformer.setSrgSourceVersion("1.20.1");
        assertEquals("getDefenseForType", transformer.remapQualifiedMethodName(MATERIAL, "m_7366_",
                "(Lnet/minecraft/world/item/ArmorItem$Type;)I"));
    }

    @Test
    @DisplayName("a version between two harvested ones takes the range that started before it")
    void gapVersionUsesThePrecedingRange() {
        transformer.setSrgSourceVersion("1.19.3");
        assertEquals("getDurabilityForSlot",
                transformer.remapQualifiedMethodName(MATERIAL, "m_7366_", SLOT_DESC));
    }

    @Test
    @DisplayName("an unknown version keeps the main table's name")
    void unknownVersionUsesTheMainTable() {
        transformer.setSrgSourceVersion(null);
        assertEquals("getDefenseForType",
                transformer.remapQualifiedMethodName(MATERIAL, "m_7366_", SLOT_DESC));
    }

    @Test
    @DisplayName("a 1.18.2 mod's m_142469_ is Entity.getBoundingBox, which 1.19 gave a new id")
    void oldBoundingBoxIdKeepsItsName() {
        transformer.setSrgSourceVersion("1.18.2");
        assertEquals("getBoundingBox", transformer.remapQualifiedMethodName(
                "net/minecraft/world/entity/Entity", "m_142469_", "()Lnet/minecraft/world/phys/AABB;"));
    }
}
