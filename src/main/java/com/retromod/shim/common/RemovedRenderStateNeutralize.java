/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

import java.util.List;

/** Drops calls to the imperative RenderSystem setters 1.21.2 to 1.21.11 removed, so old mods load. */
public final class RemovedRenderStateNeutralize {

    private RemovedRenderStateNeutralize() {}

    // RenderSystem keeps this name on every loader, Fabric's intermediary hosts included, so one
    // key serves all of them. Raw GL11 calls are left to GraphicsBackendCompat.
    private static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";

    private static final String SOURCE_OLD = "Lcom/mojang/blaze3d/platform/GlStateManager$SourceFactor;";
    private static final String DEST_OLD = "Lcom/mojang/blaze3d/platform/GlStateManager$DestFactor;";
    private static final String SOURCE_NEW = "Lcom/mojang/blaze3d/platform/SourceFactor;";
    private static final String DEST_NEW = "Lcom/mojang/blaze3d/platform/DestFactor;";

    /** A removed setter: the first version without it, its name, and its descriptor there. */
    record Removed(String since, String name, String desc) {}

    /**
     * Each setter with the version that removed it, checked with javap against every vanilla
     * client jar from 1.21.1 to 1.21.11. Render state moved onto the immutable RenderPipeline, so
     * there is nothing to redirect to: the call's arguments are popped and the mod loads, minus
     * that bit of manual GL state. A fully manual immediate-mode renderer may still draw wrong.
     */
    static final List<Removed> REMOVED = List.of(
            // 1.21.2 replaced the supplier with a compiled shader program.
            new Removed("1.21.2", "setShader", "(Ljava/util/function/Supplier;)V"),
            // 1.21.2 made getModelViewMatrix() return the model-view stack, so the copy is gone.
            new Removed("1.21.2", "applyModelViewMatrix", "()V"),
            // 1.21.5 moved blend, depth, cull, mask, clear, offset and logic-op state onto pipelines.
            new Removed("1.21.5", "enableBlend", "()V"),
            new Removed("1.21.5", "disableBlend", "()V"),
            new Removed("1.21.5", "defaultBlendFunc", "()V"),
            new Removed("1.21.5", "blendFunc", "(II)V"),
            new Removed("1.21.5", "blendFunc", "(" + SOURCE_OLD + DEST_OLD + ")V"),
            new Removed("1.21.5", "blendFuncSeparate", "(IIII)V"),
            new Removed("1.21.5", "blendFuncSeparate", "(" + SOURCE_OLD + DEST_OLD + SOURCE_OLD + DEST_OLD + ")V"),
            new Removed("1.21.5", "enableDepthTest", "()V"),
            new Removed("1.21.5", "disableDepthTest", "()V"),
            new Removed("1.21.5", "depthMask", "(Z)V"),
            new Removed("1.21.5", "depthFunc", "(I)V"),
            new Removed("1.21.5", "enableCull", "()V"),
            new Removed("1.21.5", "disableCull", "()V"),
            new Removed("1.21.5", "colorMask", "(ZZZZ)V"),
            new Removed("1.21.5", "clearColor", "(FFFF)V"),
            new Removed("1.21.5", "clearDepth", "(D)V"),
            new Removed("1.21.5", "enablePolygonOffset", "()V"),
            new Removed("1.21.5", "disablePolygonOffset", "()V"),
            new Removed("1.21.5", "polygonOffset", "(FF)V"),
            new Removed("1.21.5", "enableColorLogicOp", "()V"),
            new Removed("1.21.5", "disableColorLogicOp", "()V"),
            // Texture binds by GL id or by name went too; 1.21.5 binds a GpuTexture.
            new Removed("1.21.5", "setShaderTexture", "(II)V"),
            new Removed("1.21.5", "setShaderTexture", "(ILnet/minecraft/resources/ResourceLocation;)V"),
            // Fabric hosts before 26.1 name the identifier class by its intermediary id.
            new Removed("1.21.5", "setShaderTexture", "(ILnet/minecraft/class_2960;)V"),
            // 1.21.6 dropped the imperative scissor (enableScissorForRenderTypeDraws remains) and the shader tint.
            new Removed("1.21.6", "enableScissor", "(IIII)V"),
            new Removed("1.21.6", "disableScissor", "()V"),
            new Removed("1.21.6", "setShaderColor", "(FFFF)V"),
            new Removed("1.21.11", "lineWidth", "(F)V"));

    /** The same calls as 26.1 shapes them, after its class moves and the Identifier rename. */
    private static final List<Removed> SHAPES_26_1 = List.of(
            new Removed("26.1", "blendFunc", "(" + SOURCE_NEW + DEST_NEW + ")V"),
            new Removed("26.1", "blendFuncSeparate", "(" + SOURCE_NEW + DEST_NEW + SOURCE_NEW + DEST_NEW + ")V"),
            new Removed("26.1", "setShaderTexture", "(ILnet/minecraft/resources/Identifier;)V"));

    /** Every setter, for 26.x hosts, which lack them all. */
    public static void register(RetromodTransformer transformer) {
        for (Removed removed : REMOVED) neutralize(transformer, removed);
        for (Removed removed : SHAPES_26_1) neutralize(transformer, removed);
    }

    /** The setters the given version removed, called from that version's shim so the host gate holds. */
    public static void registerRemovedIn(RetromodTransformer transformer, String version) {
        for (Removed removed : REMOVED) {
            if (removed.since().equals(version)) neutralize(transformer, removed);
        }
    }

    private static void neutralize(RetromodTransformer transformer, Removed removed) {
        transformer.registerRemovedMethodNeutralize(RENDER_SYSTEM, removed.name(), removed.desc());
    }
}
