/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge 1.21 to 1.21.1 shim. */
public class Forge_1_21_to_1_21_1 implements VersionShim {
    
    @Override
    public String getShimName() {
        return "Forge 1.21 to 1.21.1";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.21";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.1";
    }
    
    @Override
    public String getModLoaderType() {
        return "forge";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // 1.21 made enchantments data and Enchantment a final record (code-defined enchantments).
        com.retromod.shim.common.LegacyEnchantmentBridge.register(transformer);
        // patch-level bump, no API changes
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
