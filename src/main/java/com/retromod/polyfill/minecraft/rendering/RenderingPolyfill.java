/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.rendering;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfill for removed/refactored rendering APIs across major MC versions.
 *
 * Covers two major rendering API migrations:
 * 1. GlStateManager → RenderSystem (1.17+): Fixed-function GL calls replaced
 *    by shader-based RenderSystem. Many GlStateManager methods become no-ops
 *    because core GL profile has no fixed-function pipeline.
 * 2. GuiComponent/PoseStack → GuiGraphics (1.20+): All 2D GUI drawing moved
 *    from static GuiComponent methods + PoseStack to the GuiGraphics context.
 * The RenderType block-layer getters are not handled here: the method redirects in
 * Common_1_21_11_to_26_1_ClassMoves.registerBlockLayerGetters send them to the moving-block types
 * on 26.1 and newer, and the Forge and NeoForge 1.21.10 to 1.21.11 shims register them on 1.21.11.
 */
public class RenderingPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "Minecraft Rendering API Changes";
    }

    @Override
    public String getCategory() {
        return "rendering";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            "com/mojang/blaze3d/platform/GlStateManager",
            "net/minecraft/client/gui/GuiComponent",
            "net/minecraft/client/gui/components/AbstractWidget"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{
            "com.retromod.polyfill.minecraft.rendering.embedded.GlStateManagerShim",
            "com.retromod.polyfill.minecraft.rendering.embedded.GuiComponentShim"
        };
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // Register embedded shim classes so they get injected into transformed mod JARs
        for (String cls : getPolyfillClasses()) {
            transformer.registerEmbeddedShim(cls);
        }

        // GlStateManager → RenderSystem redirects (1.17+)

        // Color state: delegates to RenderSystem.setShaderColor
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "color", "(FFFF)V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "color", "(FFFF)V"
        );

        // Alpha test: no-ops in core profile (always enabled)
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "enableAlpha", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "enableAlpha", "()V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "disableAlpha", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "disableAlpha", "()V"
        );

        // Blend: delegates to RenderSystem
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "enableBlend", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "enableBlend", "()V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "disableBlend", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "disableBlend", "()V"
        );

        // Depth test: delegates to RenderSystem
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "enableDepthTest", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "enableDepthTest", "()V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "disableDepthTest", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "disableDepthTest", "()V"
        );

        // Lighting: no-ops (no fixed-function lighting in core profile)
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "enableLighting", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "enableLighting", "()V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "disableLighting", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "disableLighting", "()V"
        );

        // Texture state: no-ops (always enabled in shader pipeline)
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "enableTexture", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "enableTexture", "()V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "disableTexture", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "disableTexture", "()V"
        );

        // Matrix stack: no-ops (use PoseStack / GuiGraphics instead)
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "pushMatrix", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "pushMatrix", "()V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "popMatrix", "()V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "popMatrix", "()V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "translate", "(DDD)V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "translate", "(DDD)V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "rotate", "(FFFF)V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "rotate", "(FFFF)V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "scale", "(DDD)V",
            "com/retromod/polyfill/minecraft/rendering/embedded/GlStateManagerShim", "scale", "(DDD)V"
        );

        // GuiComponent → GuiGraphics redirects (1.20+), on hosts before 26.1. From 26.1 the
        // LegacyPoseGui bridge owns these draws, since this shim's 1.20 path cannot link there.
        if (RetromodVersion.mcVersionExceeds("26.1", RetromodVersion.TARGET_MC_VERSION)) {
            // GuiComponent.fill(PoseStack, int, int, int, int, int) → shim that delegates to GuiGraphics
            transformer.registerMethodRedirect(
                "net/minecraft/client/gui/GuiComponent", "fill",
                "(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V",
                "com/retromod/polyfill/minecraft/rendering/embedded/GuiComponentShim", "fill",
                "(Ljava/lang/Object;IIIII)V"
            );
            // GuiComponent.blit(PoseStack, int, int, int, int, int, int) → shim
            transformer.registerMethodRedirect(
                "net/minecraft/client/gui/GuiComponent", "blit",
                "(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V",
                "com/retromod/polyfill/minecraft/rendering/embedded/GuiComponentShim", "blit",
                "(Ljava/lang/Object;IIIIII)V"
            );
        }

        // No drawString redirect. The old GuiComponent.drawString was static and took a PoseStack
        // first, so a key of (Font, String, int, int, int) never matched old code. Because the 1.20
        // shims class-redirect GuiComponent to GuiGraphics, that key became exactly the modern
        // GuiGraphics.drawString, which returns void from 1.21.6, and it turned every text draw in
        // a 1.21.6 to 1.21.11 mod into a VerifyError (#281, Hold My Items on 26.3).

        // No RenderType getter redirect. Common_1_21_11_to_26_1_ClassMoves.registerBlockLayerGetters
        // sends RenderType.solid() and friends to the RenderTypes moving-block types, the only
        // block-layer types a 26.x host has.
    }
}
