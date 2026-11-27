/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;

/** Fabric API 1.20.2 to 1.21.1's {@code ModelLoadingPlugin}, whose method 1.21.2 renamed to {@code initialize}. */
public interface LegacyModelLoadingPlugin extends ModelLoadingPlugin {
    void onInitializeModelLoader(ModelLoadingPlugin.Context pluginContext);

    @Override
    default void initialize(ModelLoadingPlugin.Context pluginContext) {
        onInitializeModelLoader(pluginContext);
    }
}
