/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/** Shim for LibGui (Cotton's Fabric GUI library): widgets, panels, screen handling. */
public class LibGuiApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "LibGui API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "4.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "10.0.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/client/CottonInventoryScreen",
            "io/github/cottonmc/cotton/gui/client/CottonInventoryScreen"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/client/LightweightGuiDescription",
            "io/github/cottonmc/cotton/gui/client/LightweightGuiDescription"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/SyncedGuiDescription",
            "io/github/cottonmc/cotton/gui/SyncedGuiDescription"
        );

        // CottonCraftingController was folded into SyncedGuiDescription
        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/CottonCraftingController",
            "io/github/cottonmc/cotton/gui/SyncedGuiDescription"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WWidget",
            "io/github/cottonmc/cotton/gui/widget/WWidget"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WPanel",
            "io/github/cottonmc/cotton/gui/widget/WPanel"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WPlainPanel",
            "io/github/cottonmc/cotton/gui/widget/WPlainPanel"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WGridPanel",
            "io/github/cottonmc/cotton/gui/widget/WGridPanel"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WCardPanel",
            "io/github/cottonmc/cotton/gui/widget/WCardPanel"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WTabPanel",
            "io/github/cottonmc/cotton/gui/widget/WTabPanel"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WButton",
            "io/github/cottonmc/cotton/gui/widget/WButton"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WLabel",
            "io/github/cottonmc/cotton/gui/widget/WLabel"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WTextField",
            "io/github/cottonmc/cotton/gui/widget/WTextField"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WToggleButton",
            "io/github/cottonmc/cotton/gui/widget/WToggleButton"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WSlider",
            "io/github/cottonmc/cotton/gui/widget/WSlider"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WItemSlot",
            "io/github/cottonmc/cotton/gui/widget/WItemSlot"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WPlayerInvPanel",
            "io/github/cottonmc/cotton/gui/widget/WPlayerInvPanel"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WBar",
            "io/github/cottonmc/cotton/gui/widget/WBar"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/WBar$Direction",
            "io/github/cottonmc/cotton/gui/widget/WBar$Direction"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/icon/Icon",
            "io/github/cottonmc/cotton/gui/widget/icon/Icon"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/icon/ItemIcon",
            "io/github/cottonmc/cotton/gui/widget/icon/ItemIcon"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/icon/TextureIcon",
            "io/github/cottonmc/cotton/gui/widget/icon/TextureIcon"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/data/Insets",
            "io/github/cottonmc/cotton/gui/widget/data/Insets"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/data/HorizontalAlignment",
            "io/github/cottonmc/cotton/gui/widget/data/HorizontalAlignment"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/widget/data/VerticalAlignment",
            "io/github/cottonmc/cotton/gui/widget/data/VerticalAlignment"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/ValidatedSlot",
            "io/github/cottonmc/cotton/gui/ValidatedSlot"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/networking/NetworkSide",
            "io/github/cottonmc/cotton/gui/networking/NetworkSide"
        );

        transformer.registerClassRedirect(
            "io/github/cottonmc/cotton/gui/networking/ScreenNetworking",
            "io/github/cottonmc/cotton/gui/networking/ScreenNetworking"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {};
    }
}
