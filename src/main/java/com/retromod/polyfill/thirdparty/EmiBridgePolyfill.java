/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.thirdparty;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Bridge polyfill for EMI 0.x -> 1.x API changes (MIT licensed).
 *
 * EMI is a popular item/recipe viewer for Minecraft (Fabric and Forge).
 * Between EMI 0.x (MC 1.19-1.19.2) and EMI 1.x (MC 1.19.3+), several
 * recipe-related classes were renamed or reorganized:
 *
 * <ul>
 *   <li>{@code EmiRecipeCategory} constructor signatures changed</li>
 *   <li>{@code EmiTexture} -> {@code EmiDrawable} (rendering helper)</li>
 *   <li>{@code EmiRender} utility methods moved</li>
 *   <li>{@code EmiPort} helper class removed (was internal compat layer)</li>
 * </ul>
 *
 * This bridge does not bundle EMI. The user installs EMI 1.x normally;
 * Retromod only redirects old 0.x class references to their 1.x equivalents.
 */
public class EmiBridgePolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "EMI 0.x -> 1.x Bridge";
    }

    @Override
    public String getCategory() {
        return "thirdparty";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            // Classes renamed/removed in EMI 1.x
            "dev/emi/emi/api/render/EmiTexture",
            "dev/emi/emi/api/render/EmiRender",
            "dev/emi/emi/runtime/EmiPort"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        // No embedded stubs needed: pure class redirects to EMI 1.x
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // EMI 0.x -> 1.x class renames.
        // The package stays dev.emi.emi.api but some classes were renamed.
        // Only REMOVED/RENAMED classes are redirected here.

        // EmiTexture -> EmiDrawable (generic drawable rendering helper)
        transformer.registerClassRedirect(
            "dev/emi/emi/api/render/EmiTexture",
            "dev/emi/emi/api/widget/TextureWidget");

        // EmiRender utility moved to EmiDrawContext in 1.x
        transformer.registerClassRedirect(
            "dev/emi/emi/api/render/EmiRender",
            "dev/emi/emi/api/EmiDrawContext");

        // EmiPort was an internal compat shim, removed in 1.x.
        // Redirect to EmiApi as the closest public entry point.
        transformer.registerClassRedirect(
            "dev/emi/emi/runtime/EmiPort",
            "dev/emi/emi/api/EmiApi");
    }
}
