/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.retromod.legacyapi.forge.eventbus.LegacyEvent;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraftforge.eventbus.api.event.InheritableEvent;
import net.minecraftforge.fml.event.IModBusEvent;

/** Forge 1.20.1's ModelEvent.RegisterAdditional, posted to each old mod before Minecraft first loads its models. */
public class LegacyRegisterAdditional extends LegacyEvent implements IModBusEvent, InheritableEvent {

    private final Set<Identifier> models;

    public LegacyRegisterAdditional(Set<Identifier> models) {
        this.models = models;
    }

    /** Asks for a model that no block state or item names to be baked too, so getModel can find it. */
    public void register(Identifier model) {
        if (model != null) models.add(model);
    }
}
