/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyDespawnResults;

import java.util.function.Predicate;

/**
 * Moves Forge's {@code MobSpawnEvent.AllowDespawn} onto NeoForge's {@code MobDespawnEvent}.
 *
 * <p>A listener class that takes the old event cannot be registered at all, because the bus
 * resolves every listener's parameter type, so one despawn listener stopped the mod's whole event
 * class. The event keeps its getters. {@code setResult} moves to the event's own result enum by
 * name. The Forge result constant the mod passes still has to resolve, which depends on how the
 * host bridges Forge's {@code Event.Result}.
 */
final class LegacyDespawnEventBridge {

    static final String FORGE = "net/minecraftforge/event/entity/living/MobSpawnEvent$AllowDespawn";
    static final String NEO = "net/neoforged/neoforge/event/entity/living/MobDespawnEvent";
    static final String HELPER = "com/retromod/shim/forge/embedded/LegacyDespawnResults";

    private LegacyDespawnEventBridge() {}

    static boolean register(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        if (hostHasClass.test(FORGE) || !hostHasClass.test(NEO)) return false;
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyDespawnResults.class);
        transformer.registerClassRedirect(FORGE, NEO);
        transformer.registerMethodRedirect(FORGE, "setResult", "(Lnet/minecraftforge/eventbus/api/Event$Result;)V",
                HELPER, "setResult", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
        return true;
    }
}
