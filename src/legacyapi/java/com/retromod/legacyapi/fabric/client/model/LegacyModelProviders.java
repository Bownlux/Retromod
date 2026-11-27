/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import java.util.function.Consumer;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** The callback types of Fabric API's v0 model loading registry, from 1.16 to 1.20.1. */
public final class LegacyModelProviders {

    private LegacyModelProviders() {}

    /** {@code ExtraModelProvider}: names models to load beside the ones block states and items use. */
    @FunctionalInterface
    public interface ExtraModelProvider {
        void provideExtraModels(ResourceManager manager, Consumer<Identifier> out);
    }

    /** {@code ModelProviderContext}: loads another model by id. */
    @FunctionalInterface
    public interface ModelProviderContext {
        UnbakedModel loadModel(Identifier id);
    }

    /** {@code ModelResourceProvider}: supplies a model for a model file id. */
    @FunctionalInterface
    public interface ModelResourceProvider {
        UnbakedModel loadModelResource(Identifier resourceId, ModelProviderContext context) throws ModelProviderException;
    }

    /** {@code ModelVariantProvider}: supplies a model for a block state variant or an item's inventory model. */
    @FunctionalInterface
    public interface ModelVariantProvider {
        UnbakedModel loadModelVariant(Identifier modelId, ModelProviderContext context) throws ModelProviderException;
    }

    /** {@code ModelProviderException}. */
    public static class ModelProviderException extends Exception {
        public ModelProviderException(String message) {
            super(message);
        }

        public ModelProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
