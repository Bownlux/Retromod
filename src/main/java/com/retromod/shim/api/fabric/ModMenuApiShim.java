/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/** Bridges Mod Menu API changes (interface/factory/badge) across v1 through v7. */
public class ModMenuApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "Mod Menu API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "7.0.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // getModConfigScreenFactory() was renamed to getConfigScreenFactory() in v2
        transformer.registerMethodRedirect(
            "com/terraformersmc/modmenu/api/ModMenuApi",
            "getModConfigScreenFactory",
            "()Ljava/util/function/Function;",
            "com/terraformersmc/modmenu/api/ModMenuApi",
            "getConfigScreenFactory",
            "()Lcom/terraformersmc/modmenu/api/ConfigScreenFactory;"
        );
        
        // io.github.prospector package moved to com.terraformersmc
        transformer.registerClassRedirect(
            "io/github/prospector/modmenu/api/ConfigScreenFactory",
            "com/terraformersmc/modmenu/api/ConfigScreenFactory"
        );
        transformer.registerClassRedirect(
            "io/github/prospector/modmenu/api/ModMenuApi",
            "com/terraformersmc/modmenu/api/ModMenuApi"
        );

        // getModBadge was removed; route it through the embedded shim
        transformer.registerMethodRedirect(
            "com/terraformersmc/modmenu/api/ModMenuApi",
            "getModBadge",
            "()Ljava/util/Optional;",
            "com/retromod/shim/api/fabric/embedded/ModMenuShim",
            "getModBadge",
            "()Ljava/util/Optional;"
        );

        // ConfigScreenFactory.create lost its generic type parameter; bridge the old signature
        transformer.registerMethodRedirect(
            "com/terraformersmc/modmenu/api/ConfigScreenFactory",
            "create",
            "(Lnet/minecraft/client/gui/screen/Screen;)Lnet/minecraft/client/gui/screen/Screen;",
            "com/retromod/shim/api/fabric/embedded/ModMenuShim",
            "createConfigScreen",
            "(Ljava/lang/Object;Lnet/minecraft/client/gui/screen/Screen;)Lnet/minecraft/client/gui/screen/Screen;"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.fabric.embedded.ModMenuShim"
        };
    }
}
