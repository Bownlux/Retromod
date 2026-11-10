/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Minecraft 1.21.1's ItemOverrides, which 1.21.4 removed; 26.x picks per-stack models itself, so these keep the model they are given. */
public class LegacyItemOverrides {

    public static final LegacyItemOverrides EMPTY = new LegacyItemOverrides();

    // Old mods subclass it with the no-argument constructor and override resolve.
    public LegacyItemOverrides() {}

    /** Minecraft 1.21.1's overrides built from a model's override list, which Patchouli's book model calls with none. */
    public LegacyItemOverrides(ModelBaker baker, BlockModel model, List<?> overrides) {
        noteDropped(overrides);
    }

    /** Forge's and NeoForge's variant that took a texture getter too. */
    public LegacyItemOverrides(ModelBaker baker, UnbakedModel model, List<?> overrides, Function<?, ?> sprites) {
        noteDropped(overrides);
    }

    // Minecraft 26.x picks per-stack models from item definitions, so an old list of overrides has nothing to feed.
    private static void noteDropped(List<?> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        LegacyLog.warnOnce("legacy item overrides list " + LegacyItemOverrides.class.getName(), "An old mod built item overrides"
                + " from a list; Minecraft 26.x reads them from item definitions, so Retromod kept the model it was given.");
    }

    /** The model to draw for the stack; the stack's own item model already chose, so this is the model given. */
    public BlockStateModel resolve(BlockStateModel model, ItemStack stack, ClientLevel level, LivingEntity entity, int seed) {
        return model;
    }
}
