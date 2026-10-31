/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Class moves and tag renames from 26.2 and 26.3 whose members carried over unchanged. */
class Mc26xMoveRefreshTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("26.2 moves the HUD enums to Hud and dripstone thickness to the speleothem property")
    void moves26_2() {
        transformer.clearRedirectsForTesting();
        Mc26_1To26_2CoreMoves.register(transformer);

        assertClassMove("net/minecraft/client/gui/Gui$HeartType", "net/minecraft/client/gui/Hud$HeartType");
        assertClassMove("net/minecraft/client/gui/Gui$ContextualInfo", "net/minecraft/client/gui/Hud$ContextualInfo");
        assertClassMove("net/minecraft/world/level/block/state/properties/DripstoneThickness",
                "net/minecraft/world/level/block/state/properties/SpeleothemThickness");
        assertFieldRename("net/minecraft/world/level/block/state/properties/BlockStateProperties",
                "DRIPSTONE_THICKNESS", "SPELEOTHEM_THICKNESS");
        assertFieldRename("net/minecraft/tags/BlockTags", "CONCRETE_POWDER", "CONCRETE_POWDERS");
    }

    @Test
    @DisplayName("26.3 moves the last Enderman goal, the clock and sound inners, and loot condition types")
    void moves26_3() {
        transformer.clearRedirectsForTesting();
        Mc26_2To26_3CoreMoves.register(transformer);

        assertClassMove("net/minecraft/world/entity/monster/EnderMan$EndermanLeaveBlockGoal",
                "net/minecraft/world/entity/monster/Enderman$EndermanLeaveBlockGoal");
        assertClassMove("net/minecraft/world/level/levelgen/structure/placement/StructurePlacement$FrequencyReductionMethod",
                "net/minecraft/world/level/levelgen/structure/placement/AbstractSpreadingStructurePlacement$FrequencyReductionMethod");
        assertClassMove("net/minecraft/client/ClientClockManager$ClockInstance",
                "net/minecraft/client/ClientClockManager$ClientClockInstance");
        assertClassMove("net/minecraft/client/resources/sounds/UnderwaterAmbientSoundInstances$SubSound",
                "net/minecraft/client/resources/sounds/UnderLiquidSubSound");
        assertClassMove("net/minecraft/world/level/storage/loot/predicates/LootItemConditions",
                "net/minecraft/world/level/storage/loot/predicates/LootItemConditionTypes");
    }

    @Test
    @DisplayName("26.3's renamed tag constants keep pointing at the same tags")
    void tagRenames26_3() {
        transformer.clearRedirectsForTesting();
        Mc26_2To26_3CoreMoves.register(transformer);

        assertFieldRename("net/minecraft/tags/BlockTags", "CONVERTABLE_TO_MUD", "CONVERTIBLE_TO_MUD");
        assertFieldRename("net/minecraft/tags/StructureTags", "ON_JUNGLE_EXPLORER_MAPS", "ON_JUNGLE_PYRAMID_MAPS");
        assertFieldRename("net/minecraft/tags/StructureTags", "ON_TRIAL_CHAMBERS_MAPS", "ON_BURIED_TRIAL_CHAMBERS_MAPS");
    }

    @Test
    @DisplayName("26.1's renamed block and item tag constants point at the tags that took their job")
    void tagRenames26_1() {
        transformer.clearRedirectsForTesting();
        Common_1_21_11_to_26_1_ClassMoves.registerTagRenames26_1(transformer);

        assertFieldRename("net/minecraft/tags/BlockTags", "BAMBOO_PLANTABLE_ON", "SUPPORTS_BAMBOO");
        assertFieldRename("net/minecraft/tags/BlockTags", "MUSHROOM_GROW_BLOCK", "OVERRIDES_MUSHROOM_LIGHT_REQUIREMENT");
        assertFieldRename("net/minecraft/tags/BlockTags", "SNOW_LAYER_CANNOT_SURVIVE_ON", "CANNOT_SUPPORT_SNOW_LAYER");
        assertFieldRename("net/minecraft/tags/ItemTags", "DYEABLE", "CAULDRON_CAN_REMOVE_DYE");
    }

    private void assertClassMove(String from, String to) {
        assertEquals(to, transformer.getClassRedirects().get(from), from);
    }

    private void assertFieldRename(String owner, String from, String to) {
        RetromodTransformer.FieldTarget target = transformer.getFieldRedirects().get(
                new RetromodTransformer.FieldKey(owner, from));
        assertNotNull(target, owner + "." + from + " has no redirect");
        assertEquals(to, target.name());
    }
}
