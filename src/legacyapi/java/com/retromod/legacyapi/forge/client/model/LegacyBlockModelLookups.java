/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** NeoForge and Fabric 1.21.1's {@code ModelManager.getModel} and {@code getMissingModel}, where an old baked model is a block state model. */
public final class LegacyBlockModelLookups {

    private LegacyBlockModelLookups() {}

    /** {@code ModelManager.getModel(location)}: a block state's model, else the missing model with one warning. */
    public static BlockStateModel getModel(ModelManager manager, Identifier location) {
        if (location == null || LegacyModelLocations.isMissing(location)) return getMissingModel(manager);
        BlockState state = stateFor(location);
        if (state != null) return manager.getBlockStateModelSet().get(state);
        // Item and standalone models are not block state models any more, and an unknown block or state has none.
        LegacyLog.warnOnce("legacy model lookup " + LegacyBlockModelLookups.class.getName(), "An old mod asked for the model "
                + location + ", which is an item or standalone model, or a block state this Minecraft version does not have."
                + " It gets the missing model, and anything it draws with it shows as the purple and black cube.");
        return getMissingModel(manager);
    }

    /** {@code ModelManager.getMissingModel()}: the purple and black cube, or null before the first model load, as before. */
    public static BlockStateModel getMissingModel(ModelManager manager) {
        // The host throws until its block models are loaded; the old getter answered null then.
        try {
            return manager.getBlockStateModelSet().missingModel();
        } catch (NullPointerException notLoadedYet) {
            return null;
        }
    }

    // Only a location with a block-state variant names a block state; inventory and standalone ones do not.
    private static BlockState stateFor(Identifier location) {
        if (!LegacyModelLocations.hasVariant(location)) return null;
        String variant = LegacyModelLocations.getVariant(location);
        if (variant.equals(LegacyModelLocations.INVENTORY) || variant.equals(LegacyModelLocations.STANDALONE)) return null;
        Block block = LegacyModelLocations.blockOf(location);
        return block == null ? null : LegacyModelLocations.stateOf(block, variant);
    }
}
