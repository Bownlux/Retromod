/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/** JEI (Just Enough Items) recipe-viewer API, v7 through v15 for Forge/NeoForge. */
public class JeiApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "JEI API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "7.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "15.0.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "forge"; // also applies to neoforge
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // IModPlugin: v7's separate registration methods were consolidated in v9+.
        transformer.registerMethodRedirect(
            "mezz/jei/api/IModPlugin",
            "registerItemSubtypes",
            "(Lmezz/jei/api/registration/ISubtypeRegistration;)V",
            "mezz/jei/api/IModPlugin",
            "registerItemSubtypes",
            "(Lmezz/jei/api/registration/ISubtypeRegistration;)V"
        );

        transformer.registerMethodRedirect(
            "mezz/jei/api/ingredients/VanillaTypes",
            "ITEM",
            "Lmezz/jei/api/ingredients/IIngredientType;",
            "com/retromod/shim/api/forge/embedded/JeiShim",
            "getItemType",
            "()Lmezz/jei/api/ingredients/IIngredientType;"
        );
        
        transformer.registerMethodRedirect(
            "mezz/jei/api/ingredients/VanillaTypes",
            "FLUID",
            "Lmezz/jei/api/ingredients/IIngredientType;",
            "com/retromod/shim/api/forge/embedded/JeiShim",
            "getFluidType",
            "()Lmezz/jei/api/ingredients/IIngredientType;"
        );

        // IRecipeCategory.getUid() became getRecipeType().
        transformer.registerMethodRedirect(
            "mezz/jei/api/recipe/category/IRecipeCategory",
            "getUid",
            "()Lnet/minecraft/resources/ResourceLocation;",
            "com/retromod/shim/api/forge/embedded/JeiShim",
            "getCategoryUid",
            "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;"
        );

        transformer.registerMethodRedirect(
            "mezz/jei/api/recipe/category/IRecipeCategory",
            "getRecipeClass",
            "()Ljava/lang/Class;",
            "com/retromod/shim/api/forge/embedded/JeiShim",
            "getRecipeClass",
            "(Ljava/lang/Object;)Ljava/lang/Class;"
        );

        // addRecipes(list, uid) became addRecipes(recipeType, list).
        transformer.registerMethodRedirect(
            "mezz/jei/api/registration/IRecipeRegistration",
            "addRecipes",
            "(Ljava/util/Collection;Lnet/minecraft/resources/ResourceLocation;)V",
            "com/retromod/shim/api/forge/embedded/JeiShim",
            "addRecipesLegacy",
            "(Ljava/lang/Object;Ljava/util/Collection;Lnet/minecraft/resources/ResourceLocation;)V"
        );

        // IGuiHandler renamed to IGuiContainerHandler.
        transformer.registerClassRedirect(
            "mezz/jei/api/gui/IGuiHandler",
            "mezz/jei/api/gui/handlers/IGuiContainerHandler"
        );

        // draw() picked up a recipeSlotsView arg and switched PoseStack for GuiGraphics.
        transformer.registerMethodRedirect(
            "mezz/jei/api/recipe/category/IRecipeCategory",
            "draw",
            "(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;DD)V",
            "com/retromod/shim/api/forge/embedded/JeiShim",
            "drawLegacy",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;DD)V"
        );

        // PoseStack is deliberately NOT redirected to GuiGraphics here. JEI swapped the parameter
        // type on its own draw(), which the redirect above already handles by taking it as Object.
        // A class redirect is global: it rewrote every PoseStack in the mod, including the 3D stack
        // an entity renderer uses, which has nothing to do with JEI. The 26.1 move then carried
        // GuiGraphics on to GuiGraphicsExtractor, so an ordinary pushPose became
        // GuiGraphicsExtractor.pushPose, a method that does not exist. Scanning 13 popular mods
        // found this in 12 of them, around 500 call sites. PoseStack is present on 26.1, 26.2 and
        // 26.3, so there is nothing to redirect it to in the first place.

        // IRecipeLayout.getItemStacks() was replaced by a different slot system.
        transformer.registerMethodRedirect(
            "mezz/jei/api/gui/IRecipeLayout",
            "getItemStacks",
            "()Lmezz/jei/api/gui/ingredient/IGuiItemStackGroup;",
            "com/retromod/shim/api/forge/embedded/JeiShim",
            "getItemStacks",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );

        // IFocus<T> getters.
        transformer.registerMethodRedirect(
            "mezz/jei/api/recipe/IFocus",
            "getValue",
            "()Ljava/lang/Object;",
            "com/retromod/shim/api/forge/embedded/JeiShim",
            "getFocusValue",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );
        
        transformer.registerMethodRedirect(
            "mezz/jei/api/recipe/IFocus",
            "getMode",
            "()Lmezz/jei/api/recipe/IFocus$Mode;",
            "com/retromod/shim/api/forge/embedded/JeiShim",
            "getFocusMode",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.forge.embedded.JeiShim"
        };
    }
}
