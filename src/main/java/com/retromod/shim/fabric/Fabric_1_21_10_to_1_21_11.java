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
        // The RenderSystem setters this version removed.
        com.retromod.shim.common.RemovedRenderStateNeutralize.registerRemovedIn(transformer, "1.21.11");
        // WorldVersion.getPackVersion(PackType) became packVersion(PackType) with a major and minor number.
        com.retromod.shim.common.LegacyPackVersionBridge.register(transformer);
        // addVertex, Transformation, and Vec3 took JOML's read-only interfaces under the same
        // intermediary names. On 26.x the intermediary-to-Mojang remap and the 26.1 shim own these.
        if (!com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            com.retromod.shim.common.JomlParameterWidening.registerIntermediary1_21_11(transformer);
            // Screen.resize lost its Minecraft argument under the same id.
            com.retromod.shim.common.LegacyGuiOverrideAdapter.registerIntermediaryResizeCalls(transformer);
        } else {
            com.retromod.shim.common.LegacyGuiOverrideAdapter.registerResizeCalls(transformer);
        }
        // Other 1.21.11 changes are additive or internal-only and need no redirect.

        // ResourceLocation renamed to Identifier in vanilla 1.21.11.
        transformer.registerClassRedirect(
            "net/minecraft/resources/ResourceLocation",
            "net/minecraft/resources/Identifier"
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
        } else {
            // A Fabric 1.21.11 host still runs intermediary names, so the same calls keep them.
            com.retromod.shim.common.LegacyCommandSourceBridge.registerIntermediary(transformer);
            // Game rules moved to a new rule type that only a server level holds. On 26.x the
            // Mojang-named bridge from the 26.1 class moves handles the remapped calls.
            com.retromod.shim.common.LegacyGameRuleCallBridge.registerIntermediary(transformer);
            // CompoundTag.remove(String) started returning the removed tag.
            com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerNbtRemoveReturnsTagIntermediary(transformer);
        }

        // Mth.sin (method_15374) and Mth.cos (method_15362) kept their intermediary names but take
        // a double from 1.21.11. Only before 26.1: there the remap reaches the Mojang-named form.
        if (!com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerMthDoubleTrig(
                    transformer, "net/minecraft/class_3532", "method_15374", "method_15362");
        }
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {};
    }
}
