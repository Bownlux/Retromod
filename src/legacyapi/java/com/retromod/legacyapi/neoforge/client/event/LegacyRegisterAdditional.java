/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import java.util.Set;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/**
 * Forge 1.20.1's and NeoForge 1.21.1's ModelEvent.RegisterAdditional on NeoForge 1.21.11 and 26.x, which replaced it with
 * standalone models. Old listeners for it register without error; NeoForge never posts it.
 */
public final class LegacyRegisterAdditional extends Event implements IModBusEvent {

    private final Set<Identifier> models;

    public LegacyRegisterAdditional(Set<Identifier> models) {
        this.models = models;
    }

    /** Forge 1.20.1's {@code register(ResourceLocation)}: asks for one extra model. */
    public void register(Identifier model) {
        models.add(model);
    }
}
