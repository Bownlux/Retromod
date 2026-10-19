/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 * 
 * Based on actual Fabric API changes documented at:
 * https://fabricmc.net/2025/12/05/12111.html
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates Fabric 1.21.10 mods for 1.21.11, the last obfuscated version and the last with Yarn mappings. */
public class Fabric_1_21_10_to_1_21_11 implements VersionShim {
    
    @Override
    public String getShimName() {
        return "Fabric 1.21.10 to 1.21.11";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.21.10";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.11";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Other 1.21.11 changes are additive or internal-only and need no redirect.

        // ResourceLocation renamed to Identifier in vanilla 1.21.11.
        transformer.registerClassRedirect(
            "net/minecraft/resources/ResourceLocation",
            "net/minecraft/resources/Identifier"
        );

        // RenderType factory methods split out into RenderTypes.
        transformer.registerClassRedirect(
            "net/minecraft/client/renderer/RenderType",
            "net/minecraft/client/renderer/RenderTypes"
        );

        // Player model-part state moved to the new Avatar superclass. Old mods can read the
        // field directly or expose it through a static @Accessor, so register both the
        // intermediary bytecode name and the old named accessor spelling.
        String dataAccessor = "Lnet/minecraft/class_2940;";
        transformer.registerFieldRedirect(
            "net/minecraft/class_1657", "field_7518", dataAccessor,
            "net/minecraft/class_11890", "field_62514", dataAccessor
        );
        transformer.registerFieldRedirect(
            "net/minecraft/class_1657", "PLAYER_MODEL_PARTS", dataAccessor,
            "net/minecraft/class_11890", "field_62514", dataAccessor
        );

        // The int permission level in CommandSourceStack's constructor became a PermissionSet.
        // The bridge matches Mojang names, which a Fabric mod only has after the 26.x remap.
        if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            com.retromod.shim.common.LegacyCommandSourceBridge.register(transformer);
        }
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {};
    }
}
