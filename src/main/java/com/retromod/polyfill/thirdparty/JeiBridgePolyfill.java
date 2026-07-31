/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.thirdparty;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Bridge polyfill for JEI (Just Enough Items) API changes (MIT licensed).
 *
 * JEI is the most popular recipe viewer for Minecraft. Between JEI 7-10
 * (targeting MC 1.16-1.19) and JEI 11+ (MC 1.19.3+), several API classes
 * were moved to sub-packages or removed:
 *
 * <ul>
 *   <li>{@code mezz.jei.api.recipe.IRecipeCategory} -> {@code mezz.jei.api.recipe.category.IRecipeCategory}</li>
 *   <li>{@code mezz.jei.api.IRecipeRegistry} -> removed (use {@code IRecipeManager})</li>
 *   <li>{@code mezz.jei.api.recipe.IRecipeHandler} -> removed in JEI 9+</li>
 *   <li>{@code mezz.jei.api.gui.IDrawable} -> {@code mezz.jei.api.gui.drawable.IDrawable}</li>
 *   <li>{@code mezz.jei.api.recipe.IRecipeWrapper} -> removed (recipes used directly)</li>
 * </ul>
 *
 * This bridge does not bundle JEI. The user installs current JEI normally;
 * Retromod only redirects old API references to their new locations.
 */
public class JeiBridgePolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "JEI (Just Enough Items) API Bridge";
    }

    @Override
    public String getCategory() {
        return "thirdparty";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            // Classes moved to sub-packages in JEI 9-11+
            "mezz/jei/api/recipe/IRecipeCategory",
            "mezz/jei/api/gui/IDrawable",
            "mezz/jei/api/recipe/IRecipeWrapper",
            "mezz/jei/api/IRecipeRegistry",
            "mezz/jei/api/recipe/IRecipeHandler",
            "mezz/jei/api/gui/IGuiItemStackGroup",
            "mezz/jei/api/gui/IRecipeLayout"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        // No embedded stubs needed: pure class redirects to current JEI
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // JEI API sub-package reorganization (JEI 9-11+).
        // Classes moved to more specific sub-packages under mezz.jei.api.
        // Only MOVED/RENAMED classes are redirected here.

        // IRecipeCategory moved to recipe.category sub-package (JEI 9+)
        transformer.registerClassRedirect(
            "mezz/jei/api/recipe/IRecipeCategory",
            "mezz/jei/api/recipe/category/IRecipeCategory");

        // IDrawable moved to gui.drawable sub-package (JEI 9+)
        transformer.registerClassRedirect(
            "mezz/jei/api/gui/IDrawable",
            "mezz/jei/api/gui/drawable/IDrawable");

        // IGuiItemStackGroup moved to gui.ingredient sub-package (JEI 9+)
        transformer.registerClassRedirect(
            "mezz/jei/api/gui/IGuiItemStackGroup",
            "mezz/jei/api/gui/ingredient/IGuiItemStackGroup");

        // IRecipeLayout moved to gui sub-package with rename (JEI 11+)
        transformer.registerClassRedirect(
            "mezz/jei/api/gui/IRecipeLayout",
            "mezz/jei/api/gui/builder/IRecipeLayoutBuilder");

        // IRecipeRegistry -> IRecipeManager (JEI 9+, different interface)
        transformer.registerClassRedirect(
            "mezz/jei/api/IRecipeRegistry",
            "mezz/jei/api/recipe/IRecipeManager");

        // IRecipeWrapper removed entirely in JEI 9 (recipes used directly).
        // Redirect to IRecipeCategory as closest surviving type.
        transformer.registerClassRedirect(
            "mezz/jei/api/recipe/IRecipeWrapper",
            "mezz/jei/api/recipe/category/IRecipeCategory");
    }
}
