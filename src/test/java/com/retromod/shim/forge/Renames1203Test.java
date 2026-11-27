/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.Legacy1203RenameBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 Forge mod's renamed members under the names 1.20.3 and NeoForge gave them (#406, #415). */
class Renames1203Test {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void clear() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void oldNameCallsTheNewOne() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Legacy1203RenameBridge.register(transformer);
        String properties = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
        var target = transformer.getMethodRedirects().get(new MethodKey(properties, "noParticlesOnBreak", "()L" + properties + ";"));
        assertNotNull(target, "an unreadable host is one from 1.21, which has only the new name");
        assertEquals("noTerrainParticles", target.name());
        assertEquals(properties, target.owner());
        var grass = transformer.getFieldRedirects().get(new RetromodTransformer.FieldKey("net/minecraft/world/level/block/Blocks", "GRASS"));
        assertNotNull(grass, "Blocks.GRASS became SHORT_GRASS");
        assertEquals("SHORT_GRASS", grass.name());
    }

    @Test
    void hostWithTheOldNamesKeepsThem() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Legacy1203RenameBridge.register(transformer);
        assertTrue(transformer.getFieldRedirects().isEmpty(), "an unreadable 1.20.2 host still has GRASS");
    }

    @Test
    void targetEventGettersTakeNeoForgeNames() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Forge_1_20_to_NeoForge_1_21.registerChangeTargetRenames(transformer);
        String event = "net/neoforged/neoforge/event/entity/living/LivingChangeTargetEvent";
        var target = transformer.getMethodRedirects().get(new MethodKey(event, "getOriginalTarget", "()Lnet/minecraft/world/entity/LivingEntity;"));
        assertNotNull(target, "an unreadable host is NeoForge from 1.21, which renamed them");
        assertEquals("getOriginalAboutToBeSetTarget", target.name());
    }
}
