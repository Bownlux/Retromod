/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates NeoForge 1.21.5 mods for 1.21.6. */
public class NeoForge_1_21_5_to_1_21_6 implements VersionShim {
    @Override public String getShimName() { return "NeoForge 1.21.5 to 1.21.6"; }
    @Override public String getSourceVersion() { return "1.21.5"; }
    @Override public String getTargetVersion() { return "1.21.6"; }
    @Override public String getModLoaderType() { return "neoforge"; }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
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

        // INBTSerializable was renamed in 1.21.6
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/common/util/INBTSerializable",
            "net/neoforged/neoforge/common/util/ValueIOSerializable"
        );
        // ItemStackHandler, FluidTank, and EnergyStorage lost their NBT save methods for ValueIO ones.
        LegacyHandlerNbtBridge.register(transformer);
        // NeoForge 21.6 moved data attachments to ValueIO, so serialize takes a MapCodec instead of a Codec.
        LegacyAttachmentCodecBridge.register(transformer);

        // LayeredDraw was removed in 1.21.6; point refs at GuiComponent (a polyfill is needed for full compat)
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/LayeredDraw",
            "net/minecraft/client/gui/GuiComponent"
        );
        // A HUD layer has the same render(graphics, deltaTracker) shape as NeoForge's GuiLayer, which
        // RegisterGuiLayersEvent takes in its place.
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/LayeredDraw$Layer",
            "net/neoforged/neoforge/client/gui/GuiLayer"
        );
    }
    
    @Override public String[] getShimClasses() { return new String[0]; }
}
