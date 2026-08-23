/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/**
 * Mekanism API shim: gas/chemical reorg, TileEntity to BlockEntity renames, recipe and energy moves.
 */
public class MekanismApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "Mekanism API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "10.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "10.5.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "forge";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // TileEntity -> BlockEntity renames
        transformer.registerClassRedirect(
            "mekanism/common/tile/TileEntityMekanism",
            "mekanism/common/tile/MekanismBlockEntity"
        );

        transformer.registerClassRedirect(
            "mekanism/common/tile/base/TileEntityMekanism",
            "mekanism/common/tile/base/MekanismBlockEntity"
        );

        // Gas/chemical reorg under api/chemical
        transformer.registerClassRedirect(
            "mekanism/api/gas/Gas",
            "mekanism/api/chemical/gas/Gas"
        );

        transformer.registerClassRedirect(
            "mekanism/api/gas/GasStack",
            "mekanism/api/chemical/gas/GasStack"
        );

        transformer.registerClassRedirect(
            "mekanism/api/gas/IGasHandler",
            "mekanism/api/chemical/gas/IGasHandler"
        );

        transformer.registerClassRedirect(
            "mekanism/api/gas/GasTank",
            "mekanism/api/chemical/gas/GasTank"
        );

        transformer.registerClassRedirect(
            "mekanism/api/infuse/InfuseType",
            "mekanism/api/chemical/infuse/InfuseType"
        );

        transformer.registerClassRedirect(
            "mekanism/api/infuse/InfusionStack",
            "mekanism/api/chemical/infuse/InfusionStack"
        );

        transformer.registerClassRedirect(
            "mekanism/api/pigment/Pigment",
            "mekanism/api/chemical/pigment/Pigment"
        );

        transformer.registerClassRedirect(
            "mekanism/api/pigment/PigmentStack",
            "mekanism/api/chemical/pigment/PigmentStack"
        );

        transformer.registerClassRedirect(
            "mekanism/api/slurry/Slurry",
            "mekanism/api/chemical/slurry/Slurry"
        );

        transformer.registerClassRedirect(
            "mekanism/api/slurry/SlurryStack",
            "mekanism/api/chemical/slurry/SlurryStack"
        );

        transformer.registerClassRedirect(
            "mekanism/api/chemical/Chemical",
            "mekanism/api/chemical/Chemical"
        );

        transformer.registerClassRedirect(
            "mekanism/api/chemical/ChemicalStack",
            "mekanism/api/chemical/ChemicalStack"
        );

        transformer.registerClassRedirect(
            "mekanism/api/chemical/IChemicalHandler",
            "mekanism/api/chemical/IChemicalHandler"
        );

        // Recipe inputs moved to recipes/ingredients
        transformer.registerClassRedirect(
            "mekanism/api/recipes/MekanismRecipe",
            "mekanism/api/recipes/MekanismRecipe"
        );

        transformer.registerClassRedirect(
            "mekanism/api/recipes/inputs/ItemStackIngredient",
            "mekanism/api/recipes/ingredients/ItemStackIngredient"
        );

        transformer.registerClassRedirect(
            "mekanism/api/recipes/inputs/FluidStackIngredient",
            "mekanism/api/recipes/ingredients/FluidStackIngredient"
        );

        transformer.registerClassRedirect(
            "mekanism/api/recipes/inputs/GasStackIngredient",
            "mekanism/api/recipes/ingredients/chemical/GasStackIngredient"
        );

        transformer.registerClassRedirect(
            "mekanism/api/energy/IEnergyContainer",
            "mekanism/api/energy/IEnergyContainer"
        );

        transformer.registerClassRedirect(
            "mekanism/api/energy/IStrictEnergyHandler",
            "mekanism/api/energy/IStrictEnergyHandler"
        );

        transformer.registerClassRedirect(
            "mekanism/api/math/FloatingLong",
            "mekanism/api/math/FloatingLong"
        );

        transformer.registerClassRedirect(
            "mekanism/common/tile/transmitter/TileEntityTransmitter",
            "mekanism/common/tile/transmitter/TransmitterBlockEntity"
        );

        transformer.registerClassRedirect(
            "mekanism/common/tile/prefab/TileEntityMultiblock",
            "mekanism/common/tile/prefab/MultiblockBlockEntity"
        );

        transformer.registerClassRedirect(
            "mekanism/common/capabilities/Capabilities",
            "mekanism/common/capabilities/MekanismCapabilities"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.forge.embedded.MekanismShim"
        };
    }
}
