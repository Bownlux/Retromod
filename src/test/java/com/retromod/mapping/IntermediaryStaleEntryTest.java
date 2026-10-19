/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bundled table was composed from 1.21.4, so ids Mojang renamed by 1.21.11 carried names no
 * 26.x host has. Minepathy and its library Zine, both 1.21.11 Fabric mods, lost their player renderer
 * class and a {@code ResourceKey} shadow to that, and Zine's missing shadow stopped Minepathy's entry
 * point on 26.2 (#271). Each corrected value was checked against the 26.1, 26.1.2, 26.2, and 26.3
 * jars by {@code scripts/harvest-intermediary-skew.py --stale}.
 */
class IntermediaryStaleEntryTest {

    @Test
    @DisplayName("Classes renamed by 1.21.11 map to the name the 26.x host uses")
    void renamedClassesMapToHostNames() {
        IntermediaryToMojangMapper m = IntermediaryToMojangMapper.getInstance();
        assertEquals("net/minecraft/client/renderer/entity/player/AvatarRenderer",
                m.mapClass("net/minecraft/class_1007"), "PlayerRenderer is gone from every 26.x host");
        assertEquals("net/minecraft/client/renderer/entity/state/AvatarRenderState",
                m.mapClass("net/minecraft/class_10055"));
    }

    @Test
    @DisplayName("Members renamed by 1.21.11 map to the name the 26.x host uses")
    void renamedMembersMapToHostNames() {
        IntermediaryToMojangMapper m = IntermediaryToMojangMapper.getInstance();
        assertEquals("identifier", m.mapField("field_25138"),
                "Zine shadows ResourceKey.location, which every 26.x host calls identifier");
        assertEquals("isWithinEntityInteractionRange", m.mapMethod("method_56092"),
                "Player.canInteractWithEntity was renamed by 1.21.11");
    }

    @Test
    @DisplayName("A renamed member that a shim redirects by its old name keeps the old name")
    void redirectedOldNamesStay() {
        IntermediaryToMojangMapper m = IntermediaryToMojangMapper.getInstance();
        assertEquals("RULE_MOBGRIEFING", m.mapField("field_19388"),
                "the GameRules redirect keyed on the old name also converts the GameRules$Key a"
                        + " pre-1.21.11 mod reads, which the 1.21.11 name would skip");

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            Common_1_21_11_to_26_1_ClassMoves.registerGameRuleFieldRenames(transformer);
            RetromodTransformer.FieldTarget rename = transformer.getFieldRedirects().get(
                    new RetromodTransformer.FieldKey("net/minecraft/world/level/GameRules", "RULE_MOBGRIEFING"));
            assertNotNull(rename, "the old name is what the descriptor-converting redirect is keyed on");
            assertEquals("MOB_GRIEFING", rename.name());
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }
}
