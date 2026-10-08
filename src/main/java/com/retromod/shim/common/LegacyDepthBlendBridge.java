/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

/**
 * Render-pipeline depth and blend arguments of 1.21.x custom pipelines.
 *
 * <p>26.1 removed {@code DepthTestFunction}, {@code withDepthTestFunction}, and
 * {@code withDepthWrite} in favor of a {@code DepthStencilState}; those calls go to
 * {@link com.retromod.polyfill.minecraft.LegacyDepthTest}. 26.2 merged {@code SourceFactor} and
 * {@code DestFactor} into {@code BlendFactor} with the same constant names, so both become class
 * moves and {@code new BlendFunction(source, dest)} meets its two-factor constructor. The move
 * takes precedence over the 26.2 static-field nuller for those enums, which had no successor
 * when it was written; the old {@code blendFunc} overloads stay neutralized. Hold My Items
 * builds such a pipeline for its in-hand particles, which its default scripts spawn for torches.
 */
public final class LegacyDepthBlendBridge {

    private static final String BUILDER = "com/mojang/blaze3d/pipeline/RenderPipeline$Builder";
    private static final String DEPTH_POLYFILL = "com/retromod/polyfill/minecraft/LegacyDepthTest";

    private LegacyDepthBlendBridge() {}

    /** 26.1 and newer. */
    public static void registerDepthState(RetromodTransformer t) {
        Common_1_21_11_to_26_1_ClassMoves.ensureSyntheticRegistered(t, DEPTH_POLYFILL);
        Common_1_21_11_to_26_1_ClassMoves.ensureSyntheticRegistered(t, DEPTH_POLYFILL + "$State");
        t.registerClassRedirect("com/mojang/blaze3d/platform/DepthTestFunction", DEPTH_POLYFILL);
        String builder = "L" + BUILDER + ";";
        t.registerMethodRedirect(BUILDER, "withDepthTestFunction",
                "(Lcom/mojang/blaze3d/platform/DepthTestFunction;)" + builder,
                DEPTH_POLYFILL, "withDepthTestFunction", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        t.registerMethodRedirect(BUILDER, "withDepthWrite", "(Z)" + builder,
                DEPTH_POLYFILL, "withDepthWrite", "(Ljava/lang/Object;Z)Ljava/lang/Object;");
    }

    /** 26.2 and newer. */
    public static void registerBlendFactors(RetromodTransformer t) {
        String factor = "com/mojang/blaze3d/platform/BlendFactor";
        t.registerClassRedirect("com/mojang/blaze3d/platform/SourceFactor", factor);
        t.registerClassRedirect("com/mojang/blaze3d/platform/DestFactor", factor);
        // The imperative blend calls stay removed. After the move their descriptors name
        // BlendFactor, so the neutralizer needs that spelling as well.
        String one = "L" + factor + ";";
        String renderSystem = "com/mojang/blaze3d/systems/RenderSystem";
        t.registerRemovedMethodNeutralize(renderSystem, "blendFunc", "(" + one + one + ")V");
        t.registerRemovedMethodNeutralize(renderSystem, "blendFuncSeparate", "(" + one + one + one + one + ")V");
    }
}
