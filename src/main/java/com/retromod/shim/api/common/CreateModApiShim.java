/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/**
 * Create mod API shim for the addons that depend on its kinetics/processing API.
 * Covers the 0.3.x package restructure, 0.4.x kinetic API, and 0.5.x 1.20+ moves.
 */
public class CreateModApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "Create Mod API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "0.3.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "0.6.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "common";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // package moves
        transformer.registerClassRedirect(
            "com/simibubi/create/content/contraptions/base/KineticTileEntity",
            "com/simibubi/create/content/kinetics/base/KineticBlockEntity"
        );

        transformer.registerClassRedirect(
            "com/simibubi/create/content/contraptions/components/mixer/MixingRecipe",
            "com/simibubi/create/content/kinetics/mixer/MixingRecipe"
        );

        // TileEntity -> BlockEntity
        transformer.registerClassRedirect(
            "com/simibubi/create/foundation/tileEntity/SmartTileEntity",
            "com/simibubi/create/foundation/blockEntity/SmartBlockEntity"
        );

        transformer.registerClassRedirect(
            "com/simibubi/create/foundation/tileEntity/TileEntityBehaviour",
            "com/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour"
        );

        // kinetic API
        transformer.registerMethodRedirect(
            "com/simibubi/create/content/contraptions/base/KineticTileEntity",
            "getSpeed",
            "()F",
            "com/simibubi/create/content/kinetics/base/KineticBlockEntity",
            "getSpeed",
            "()F"
        );

        transformer.registerMethodRedirect(
            "com/simibubi/create/content/contraptions/base/KineticTileEntity",
            "calculateStressApplied",
            "()F",
            "com/simibubi/create/content/kinetics/base/KineticBlockEntity",
            "calculateStressApplied",
            "()F"
        );

        // processing recipes
        transformer.registerClassRedirect(
            "com/simibubi/create/content/contraptions/processing/ProcessingRecipe",
            "com/simibubi/create/content/processing/recipe/ProcessingRecipe"
        );

        transformer.registerClassRedirect(
            "com/simibubi/create/content/contraptions/processing/ProcessingRecipeBuilder",
            "com/simibubi/create/content/processing/recipe/ProcessingRecipeBuilder"
        );

        transformer.registerClassRedirect(
            "com/simibubi/create/foundation/fluid/SmartFluidTank",
            "com/simibubi/create/foundation/fluid/SmartFluidTank"
        );

        // ponder system
        transformer.registerClassRedirect(
            "com/simibubi/create/foundation/ponder/PonderRegistry",
            "com/simibubi/create/infrastructure/ponder/PonderIndex"
        );

        transformer.registerClassRedirect(
            "com/simibubi/create/content/schematics/SchematicWorld",
            "com/simibubi/create/content/schematics/SchematicWorld"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {};
    }
}
