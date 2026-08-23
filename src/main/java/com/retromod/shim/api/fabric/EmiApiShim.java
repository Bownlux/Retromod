/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/** Fabric shim for the EMI recipe-viewer API. */
public class EmiApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "EMI API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "0.1.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.1.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerMethodRedirect(
            "dev/emi/emi/api/stack/EmiStack",
            "of",
            "(Lnet/minecraft/item/ItemStack;)Ldev/emi/emi/api/stack/EmiStack;",
            "dev/emi/emi/api/stack/EmiStack",
            "of",
            "(Lnet/minecraft/item/ItemStack;)Ldev/emi/emi/api/stack/EmiStack;"
        );

        transformer.registerClassRedirect(
            "dev/emi/emi/api/EmiPlugin",
            "dev/emi/emi/api/EmiPlugin"
        );

        transformer.registerMethodRedirect(
            "dev/emi/emi/api/EmiRegistry",
            "addCategory",
            "(Ldev/emi/emi/api/recipe/EmiRecipeCategory;)V",
            "dev/emi/emi/api/EmiRegistry",
            "addCategory",
            "(Ldev/emi/emi/api/recipe/EmiRecipeCategory;)V"
        );

        transformer.registerClassRedirect(
            "dev/emi/emi/api/recipe/EmiRecipe",
            "dev/emi/emi/api/recipe/EmiRecipe"
        );

        transformer.registerClassRedirect(
            "dev/emi/emi/api/recipe/BasicEmiRecipe",
            "dev/emi/emi/api/recipe/BasicEmiRecipe"
        );

        transformer.registerMethodRedirect(
            "dev/emi/emi/api/widget/WidgetHolder",
            "addSlot",
            "(Ldev/emi/emi/api/stack/EmiIngredient;II)Ldev/emi/emi/api/widget/SlotWidget;",
            "dev/emi/emi/api/widget/WidgetHolder",
            "addSlot",
            "(Ldev/emi/emi/api/stack/EmiIngredient;II)Ldev/emi/emi/api/widget/SlotWidget;"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {};
    }
}
