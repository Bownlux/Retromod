/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

/**
 * Stubs out the imperative {@code RenderSystem} state setters deleted in the blaze3d
 * GPU refactor (enableBlend/blendFunc/depthMask/colorMask/...). They're gone from
 * 1.21.11 on with no replacement to redirect to (render state moved onto the immutable
 * {@code RenderPipeline}), so an old mod (~1.16-1.21.4) calling them would hit
 * {@code NoSuchMethodError} at link time. We pop the args and push a default return:
 * the mod loads, minus that one bit of manual GL state. A fully manual immediate-mode
 * renderer may blend wrong and needs hand-porting (roadmap).
 *
 * <p>Match is on owner+name+descriptor, and every entry below is absent on 1.21.11+,
 * so a wrong descriptor just fails to match. {@code RenderSystem} keeps its
 * {@code com.mojang.blaze3d} name on all three loaders. Raw {@code GL11.*} calls are
 * left to {@code GraphicsBackendCompat}.
 */
public final class RemovedRenderStateNeutralize {

    private RemovedRenderStateNeutralize() {}

    private static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";

    public static void register(RetromodTransformer transformer) {
        // blend
        neutralize(transformer, "enableBlend", "()V");
        neutralize(transformer, "disableBlend", "()V");
        neutralize(transformer, "defaultBlendFunc", "()V");
        neutralize(transformer, "blendFunc", "(II)V");
        neutralize(transformer, "blendFuncSeparate", "(IIII)V");
        // The enum overloads 1.21.x mods actually call. Registered in BOTH spellings: the raw
        // GlStateManager$SourceFactor/$DestFactor inners (pre-class-move; also the literal
        // GlStateManager$class_4534/4535 hybrids map to the promoted names first) and the promoted
        // 26.1 top-level SourceFactor/DestFactor the class-move rewrites descriptors to. On 26.2
        // the promoted enums are deleted too; CoreMoves nulls their GETSTATICs, so the popped args
        // here are just ACONST_NULLs.
        String srcOld = "Lcom/mojang/blaze3d/platform/GlStateManager$SourceFactor;";
        String dstOld = "Lcom/mojang/blaze3d/platform/GlStateManager$DestFactor;";
        String srcNew = "Lcom/mojang/blaze3d/platform/SourceFactor;";
        String dstNew = "Lcom/mojang/blaze3d/platform/DestFactor;";
        neutralize(transformer, "blendFunc", "(" + srcOld + dstOld + ")V");
        neutralize(transformer, "blendFunc", "(" + srcNew + dstNew + ")V");
        neutralize(transformer, "blendFuncSeparate", "(" + srcOld + dstOld + srcOld + dstOld + ")V");
        neutralize(transformer, "blendFuncSeparate", "(" + srcNew + dstNew + srcNew + dstNew + ")V");
        // depth
        neutralize(transformer, "enableDepthTest", "()V");
        neutralize(transformer, "disableDepthTest", "()V");
        neutralize(transformer, "depthMask", "(Z)V");
        neutralize(transformer, "depthFunc", "(I)V");
        // cull / color mask
        neutralize(transformer, "enableCull", "()V");
        neutralize(transformer, "disableCull", "()V");
        neutralize(transformer, "colorMask", "(ZZZZ)V");
        // clear
        neutralize(transformer, "clearColor", "(FFFF)V");
        neutralize(transformer, "clearDepth", "(D)V");
        // scissor (imperative form; 26.x keeps enableScissorForRenderTypeDraws)
        neutralize(transformer, "enableScissor", "(IIII)V");
        neutralize(transformer, "disableScissor", "()V");
        // polygon offset + line width
        neutralize(transformer, "enablePolygonOffset", "()V");
        neutralize(transformer, "disablePolygonOffset", "()V");
        neutralize(transformer, "polygonOffset", "(FF)V");
        neutralize(transformer, "lineWidth", "(F)V");
        // color logic op
        neutralize(transformer, "enableColorLogicOp", "()V");
        neutralize(transformer, "disableColorLogicOp", "()V");
        // Imperative shader binding, deleted in the 26.x GpuDevice/RenderPipeline refactor (shaders
        // moved onto pipeline objects). setShader(Supplier)/setShaderColor(FFFF)/setShaderTexture(
        // int,Identifier) are gone (verified absent on 26.2; only setShaderFog/setShaderLights
        // remain), so a 1.21.x mod calling them dies NoSuchMethodError at RENDER time (a game crash
        // when the mod draws). Neutralizing turns that into a soft-fail: the custom shader/tint/
        // texture bind is lost (rendering may look wrong) but the game doesn't crash. Top-frequency
        // in the 87-mod corpus re-audit (setShader 26 mods, setShaderTexture 18). The
        // setShaderTexture(int,int) glId overload is left alone (a still-present low-level form).
        neutralize(transformer, "setShader", "(Ljava/util/function/Supplier;)V");
        neutralize(transformer, "setShaderColor", "(FFFF)V");
        neutralize(transformer, "setShaderTexture", "(ILnet/minecraft/resources/Identifier;)V");
    }

    private static void neutralize(RetromodTransformer transformer, String name, String desc) {
        transformer.registerRemovedMethodNeutralize(RENDER_SYSTEM, name, desc);
    }
}
