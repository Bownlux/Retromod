/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.brewing;

import net.minecraft.core.RegistryAccess;
import net.neoforged.bus.api.Event;

/** NeoForge 1.21.1's brewing registration event, which NeoForge 26.3 removed; Retromod posts it once each server has started. */
public class LegacyRegisterBrewingRecipesEvent extends Event {

    private final LegacyPotionBrewingBuilder builder;
    private final RegistryAccess registryAccess;

    public LegacyRegisterBrewingRecipesEvent(LegacyPotionBrewingBuilder builder, RegistryAccess registryAccess) {
        this.builder = builder;
        this.registryAccess = registryAccess;
    }

    /** The builder the old handler adds its mixes and recipes to. */
    public LegacyPotionBrewingBuilder getBuilder() {
        return builder;
    }

    public RegistryAccess getRegistryAccess() {
        return registryAccess;
    }
}
