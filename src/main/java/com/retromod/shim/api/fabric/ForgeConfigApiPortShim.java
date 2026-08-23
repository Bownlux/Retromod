/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/** Fabric port of Forge's config system, used by many mods for cross-loader config. */
public class ForgeConfigApiPortShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "Forge Config API Port Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "8.0.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // The port ships the Forge config classes (ForgeConfigSpec, ModConfig) at their
        // original names, so mod references already resolve. Redirecting onto the port's
        // own fuzs.forgeconfigapiport API broke at runtime instead (#94).
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {};
    }
}
