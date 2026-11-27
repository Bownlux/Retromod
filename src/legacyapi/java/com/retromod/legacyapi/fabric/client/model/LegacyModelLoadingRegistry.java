/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import java.util.function.Function;
import net.minecraft.server.packs.resources.ResourceManager;

/** Fabric API's v0 {@code ModelLoadingRegistry}, from 1.16 to 1.20.1, served by one 26.x model loading plugin. */
public interface LegacyModelLoadingRegistry {
    LegacyModelLoadingRegistry INSTANCE = new LegacyModelLoadingRegistry() {};

    default void registerModelProvider(LegacyModelProviders.ExtraModelProvider provider) {
        if (provider != null) LegacyModelLoading.registerV0(() -> LegacyModelLoading.EXTRA_PROVIDERS.add(provider));
    }

    default void registerResourceProvider(Function<ResourceManager, LegacyModelProviders.ModelResourceProvider> factory) {
        if (factory != null) LegacyModelLoading.registerV0(() -> LegacyModelLoading.RESOURCE_PROVIDERS.add(factory));
    }

    default void registerVariantProvider(Function<ResourceManager, LegacyModelProviders.ModelVariantProvider> factory) {
        if (factory != null) LegacyModelLoading.registerV0(() -> LegacyModelLoading.VARIANT_PROVIDERS.add(factory));
    }
}
