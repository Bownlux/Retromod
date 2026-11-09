/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.listener.Priority;

/** This copy's host listeners for old geometry, added once, on a client only. */
final class LegacyGeometryHooks {

    private LegacyGeometryHooks() {}

    static void attach() {
        // Every reload starts by collecting geometry loaders, so what the last bake kept is dropped there.
        ModelEvent.RegisterGeometryLoaders.BUS.addListener(Priority.HIGHEST, event -> LegacyGeometryBakes.startReload());
        // Retromod's builtin loaders come after Forge's own, so a host that has one keeps it.
        ModelEvent.RegisterGeometryLoaders.BUS.addListener(Priority.LOWEST, LegacyBuiltinLoaders::register);
        // Old geometry that draws per block or stack is put back before old ModifyBakingResult listeners read the maps.
        ModelEvent.ModifyBakingResult.BUS.addListener(Priority.HIGHEST, LegacyGeometryBakes::swap);
    }
}
