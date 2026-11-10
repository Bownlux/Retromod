/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/** The old RegisterGeometryLoaders event on NeoForge 26.x, which old listeners register for and NeoForge never posts. */
public final class LegacyRegisterGeometryLoaders extends Event implements IModBusEvent {

    // NeoForge 26.x replaced geometry loaders with model loaders, so an old loader handed here bakes nothing.
    public LegacyRegisterGeometryLoaders() {}

    /** NeoForge 1.21.1's {@code register(ResourceLocation, IGeometryLoader)}, with the loader the old mod made. */
    public void register(Identifier id, Object loader) {
    }

    /** Forge 1.20.1's {@code register(String, IGeometryLoader)}, which took the loader's path in the mod's namespace. */
    public void register(String name, Object loader) {
    }
}
