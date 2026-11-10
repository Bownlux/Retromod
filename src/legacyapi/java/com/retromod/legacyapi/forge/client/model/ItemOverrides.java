/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Forge 1.20.1's ItemOverrides, which picks the model an item stack draws; this one keeps the model it is given. */
public class ItemOverrides {

    public static final ItemOverrides EMPTY = new ItemOverrides();

    // Old mods subclass it with the no-argument constructor and override resolve.
    public ItemOverrides() {}

    /** Minecraft 1.20.1's overrides built from a model's override list, which Patchouli's book model calls with none. */
    public ItemOverrides(ModelBaker baker, BlockModel model, List<?> overrides) {
        noteDropped(overrides);
    }

    /** Forge 1.20.1's variant that took a texture getter too. */
    public ItemOverrides(ModelBaker baker, UnbakedModel model, List<?> overrides, Function<?, ?> sprites) {
        noteDropped(overrides);
    }

    // Minecraft 26.2 picks per-stack models from item definitions, which Retromod writes from the model files.
    private static void noteDropped(List<?> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        LegacyLog.warnOnce("legacy item overrides list " + ItemOverrides.class.getName(), "An old mod built item overrides"
                + " from a list; Minecraft 26.2 reads them from item definitions, so Retromod kept the model it was given.");
    }

    /** The model to draw for the stack; null makes Retromod draw the model it started from. */
    public BakedModel resolve(BakedModel model, ItemStack stack, ClientLevel level, LivingEntity entity, int seed) {
        return model;
    }
}
