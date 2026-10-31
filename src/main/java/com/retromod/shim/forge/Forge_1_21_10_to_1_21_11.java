/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates Forge 1.21.10 mods for 1.21.11. */
public class Forge_1_21_10_to_1_21_11 implements VersionShim {
    @Override public String getShimName() { return "Forge 1.21.10 to 1.21.11"; }
    @Override public String getSourceVersion() { return "1.21.10"; }
    @Override public String getTargetVersion() { return "1.21.11"; }
    @Override public String getModLoaderType() { return "forge"; }
    @Override public void registerRedirects(RetromodTransformer transformer) {
        // The int permission level in CommandSourceStack's constructor became a PermissionSet.
        com.retromod.shim.common.LegacyCommandSourceBridge.register(transformer);
        // 1.21.11 moved game rules to gamerules.GameRules under new names, which only a server
        // level holds. Fabric kept its field ids, so only the Mojang-named loaders need this.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerGameRuleFieldRenames(transformer);
        com.retromod.shim.common.LegacyGameRuleCallBridge.register(transformer);
        // SimpleCraftingRecipeSerializer became CustomRecipe.Serializer.
        com.retromod.shim.common.LegacyRecipeSerializerShim.registerCustomRecipeRename(transformer);
    }
    @Override public String[] getShimClasses() { return new String[0]; }
}
