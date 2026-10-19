/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 * 
 * Based on actual Fabric API changes documented at:
 * https://fabricmc.net/2025/06/15/1216.html
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates Fabric 1.21.5 mods for 1.21.6 and newer. */
public class Fabric_1_21_5_to_1_21_6 implements VersionShim {
    
    @Override
    public String getShimName() {
        return "Fabric 1.21.5 to 1.21.6";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.21.5";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.6";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {

        // ServerPlayer.server became private. The bridge matches Mojang names, which a Fabric
        // mod only has after the 26.x remap.
        if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            com.retromod.shim.common.LegacyPlayerServerBridge.register(transformer);
        }

        // HUD API rewrite: HudRenderCallback -> HudElementRegistry
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback",
            "com/retromod/shim/fabric/embedded/HudRenderCallbackShim"
        );

        // Material API removed from the Fabric Rendering API
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/renderer/v1/material/MaterialFinder",
            "com/retromod/shim/fabric/embedded/MaterialFinderShim"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/renderer/v1/material/RenderMaterial",
            "com/retromod/shim/fabric/embedded/RenderMaterialShim"
        );

        // CoreShaderRegistrationCallback removed; core shaders now ship in resource packs
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/CoreShaderRegistrationCallback",
            "com/retromod/shim/fabric/embedded/CoreShaderRegistrationCallbackShim"
        );

        // WorldRenderer.drawBox moved to VertexRendering
        transformer.registerMethodRedirect(
            "net/minecraft/client/render/WorldRenderer", "drawBox",
            "(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;DDDDDDFFFF)V",
            "com/retromod/shim/fabric/embedded/WorldRendererShim", "drawBox",
            "(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;DDDDDDFFFF)V"
        );

        // LayeredDraw folded into the HudElementRegistry rewrite; stub it
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/LayeredDraw",
            "com/retromod/shim/fabric/embedded/LayeredDrawStub"
        );

        // Font.drawInBatch return type changed int -> float; needs a polyfill, not a redirect,
        // so it's left alone (callers that discard the value are unaffected).
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.fabric.embedded.HudRenderCallbackShim",
            "com.retromod.shim.fabric.embedded.MaterialFinderShim",
            "com.retromod.shim.fabric.embedded.RenderMaterialShim",
            "com.retromod.shim.fabric.embedded.CoreShaderRegistrationCallbackShim",
            "com.retromod.shim.fabric.embedded.WorldRendererShim",
            "com.retromod.shim.fabric.embedded.LayeredDrawStub"
        };
    }
}
