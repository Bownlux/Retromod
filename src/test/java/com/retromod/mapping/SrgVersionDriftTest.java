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

import java.util.List;

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
    private static final String ENTITY = "net/minecraft/world/entity/Entity";

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
    @DisplayName("a 1.19.3 mod's m_7366_ is still getDurabilityForSlot, which 1.19.4 replaced")
    void lastVersionBeforeTheChangeKeepsTheOldName() {
        transformer.setSrgSourceVersion("1.19.3");
        assertEquals("getDurabilityForSlot",
                transformer.remapQualifiedMethodName(MATERIAL, "m_7366_", SLOT_DESC));
    }

    @Test
    @DisplayName("a version no range lists takes the range that started before it")
    void gapVersionUsesThePrecedingRange() {
        transformer.registerSrgVersionDrift("METHOD", "m_999999_", List.of(
                new RetromodTransformer.SrgDrift("1.17.1", "1.18.2", "olderName"),
                new RetromodTransformer.SrgDrift("1.19.4", "1.21.8", "newerName")));
        transformer.setSrgSourceVersion("1.19.2");
        assertEquals("olderName", transformer.remapQualifiedMethodName(ENTITY, "m_999999_", "()V"));
    }

    @Test
    @DisplayName("#283: a mod whose minecraft range starts at 1.20 gets the 1.20 names, not 1.19.4's")
    void modBuiltForMinecraft120UsesItsOwnNames() {
        // Scorched Guns declares [1.20, 1.20.2). Without a 1.20 harvest, 1.20 fell back to the
        // 1.17 to 1.19.4 range, so every level() call became getLevel() and failed to link.
        transformer.setSrgSourceVersion("1.20");
        assertEquals("level", transformer.remapQualifiedMethodName(
                ENTITY, "m_9236_", "()Lnet/minecraft/world/level/Level;"));
        assertEquals("onGround", transformer.remapQualifiedMethodName(ENTITY, "m_20096_", "()Z"));
    }

    @Test
    @DisplayName("a 1.19 mod's m_214076_ is wasKilled, the name it had until 1.19.4")
    void modBuiltForMinecraft119UsesItsOwnNames() {
        transformer.setSrgSourceVersion("1.19");
        assertEquals("wasKilled", transformer.remapQualifiedMethodName(ENTITY, "m_214076_",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)Z"));
    }

    @Test
    @DisplayName("an unknown version keeps the main table's name")
    void unknownVersionUsesTheMainTable() {
        transformer.setSrgSourceVersion(null);
        assertEquals("getDefenseForType",
                transformer.remapQualifiedMethodName(MATERIAL, "m_7366_", SLOT_DESC));
    }

    @Test
    @DisplayName("selecting the offline namespace keeps the mod's version")
    void offlineNamespaceSetupKeepsTheVersion() {
        // The CLI transform command sets the version first, and its 26.x mapping step then
        // rebuilds the SRG tables for the mod's loader.
        transformer.setSrgSourceVersion("1.19.2");
        OfflineLoaderNameMappings.configure(transformer, "forge", "26.1", false);
        assertEquals("getDurabilityForSlot",
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
