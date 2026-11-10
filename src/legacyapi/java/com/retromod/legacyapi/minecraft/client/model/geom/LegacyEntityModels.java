/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.model.geom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.resources.model.ModelManager;

import java.util.Map;

/** The entity model set an old mod gets from Minecraft.getEntityModels(), which bakes from the game's current set. */
public final class LegacyEntityModels extends EntityModelSet {

    // One set answers every call and stays valid across reloads, as the single set of 1.20.1 did.
    private static final LegacyEntityModels MODELS = new LegacyEntityModels();

    private LegacyEntityModels() {
        super(Map.of());
    }

    /** Answers an old getEntityModels() call, which may come while items register, before the game has a model manager. */
    public static EntityModelSet entityModels(Minecraft minecraft) {
        return MODELS;
    }

    @Override
    public ModelPart bakeLayer(ModelLayerLocation layer) {
        EntityModelSet current = current();
        // Before the game has a model manager this set has no layers, as 1.20.1's had none before its first reload.
        return current == null ? super.bakeLayer(layer) : current.bakeLayer(layer);
    }

    // The game builds a new set on every resource reload, so the model manager is asked each time.
    private static EntityModelSet current() {
        Minecraft minecraft = Minecraft.getInstance();
        ModelManager manager = minecraft == null ? null : minecraft.getModelManager();
        return manager == null ? null : manager.entityModels().get();
    }
}
