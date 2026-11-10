/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates Forge 1.21.5 mods for 1.21.6. */
public class Forge_1_21_5_to_1_21_6 implements VersionShim {
    @Override public String getShimName() { return "Forge 1.21.5 to 1.21.6"; }
    @Override public String getSourceVersion() { return "1.21.5"; }
    @Override public String getTargetVersion() { return "1.21.6"; }
    @Override public String getModLoaderType() { return "forge"; }
    @Override public void registerRedirects(RetromodTransformer transformer) {
        // The RenderSystem setters this version removed.
        com.retromod.shim.common.RemovedRenderStateNeutralize.registerRemovedIn(transformer, "1.21.6");
        // Font.drawInBatch stopped returning the end x.
        com.retromod.shim.common.LegacyRenderCallBridge.registerVoidDrawInBatch(transformer);
        // ServerPlayer.server became private.
        com.retromod.shim.common.LegacyPlayerServerBridge.register(transformer);
        // SoundManager.play returns a PlayResult, and GameNarrator.sayNow is saySystemNow.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerSoundPlayResult(transformer);
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerNarratorSayNowRename(transformer);
        // ItemStack.parse and save, and ContainerHelper's CompoundTag forms, went with value input and output.
        com.retromod.shim.common.LegacyItemNbtApi.register1216(transformer);
        // GUI drawing became queued state: drawString returns nothing, flush is gone, and sprites
        // take a render pipeline. The 2D pose() rewrite turns rotations into RetroQuat2D calls.
        com.retromod.shim.common.LegacyGuiDrawBridge.register1216(transformer);
        com.retromod.shim.common.LegacyGuiGraphicsBridge.register1216(transformer);
        com.retromod.shim.common.Quat2DSynthetic.register(transformer);
        // Save overrides took a CompoundTag. The host now passes a ValueOutput or ValueInput.
        com.retromod.shim.common.LegacyValueIoOverrideAdapter.register(transformer);

        // RenderType.translucent() went away in 1.21.6; translucentMovingBlock() is the same layer.
        // 1.21.11 moved both, and its own shim maps the old call there.
        if (com.retromod.core.RetromodVersion.compareMcVersions(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.21.11") < 0) {
            String renderType = "()Lnet/minecraft/client/renderer/RenderType;";
            transformer.registerMethodRedirect("net/minecraft/client/renderer/RenderType", "translucent", renderType,
                    "net/minecraft/client/renderer/RenderType", "translucentMovingBlock", renderType);
        }
    }
    @Override public String[] getShimClasses() { return new String[0]; }
}
