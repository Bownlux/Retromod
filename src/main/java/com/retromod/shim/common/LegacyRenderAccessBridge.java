/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.function.Function;

/** Links the render accessors 1.20.1 and 1.21.1 mods call that 26.x removed or moved, each checked against the host. */
public final class LegacyRenderAccessBridge {

    static final String SHADERS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyShaders";
    static final String MODEL_VIEW = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyModelView";
    static final String LEVEL_CALLS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyLevelRenderCalls";
    static final String TEXTURE_CALLS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyTextureCalls";

    static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
    static final String LEVEL_EXTRACTOR = "net/minecraft/client/renderer/extract/LevelExtractor";
    static final String LIGHT_COORDS = "net/minecraft/util/LightCoordsUtil";
    static final String ABSTRACT_TEXTURE = "net/minecraft/client/renderer/texture/AbstractTexture";

    static final String POSE_STACK = "Lcom/mojang/blaze3d/vertex/PoseStack;";
    static final String RENDER_TARGET = "Lcom/mojang/blaze3d/pipeline/RenderTarget;";
    static final String BLOCK_POS = "Lnet/minecraft/core/BlockPos;";
    static final String BLOCK_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    static final String LIGHT_GETTER = "Lnet/minecraft/world/level/BlockAndLightGetter;";
    static final String NEW_LIGHT = "(" + LIGHT_GETTER + BLOCK_POS + ")I";

    /** The old shader type under each name a mod's descriptor can carry by the time it is redirected. */
    static final List<String> SHADER_TYPES = List.of("net/minecraft/client/renderer/ShaderInstance",
            "net/minecraft/client/renderer/CompiledShaderProgram", "com/mojang/blaze3d/opengl/GlProgram",
            "com/mojang/renderpearl/backend/opengl/GlProgram");

    /** The tint getter under its 1.20.1 package and the client package 26.1 moved it to. */
    static final List<String> TINT_GETTERS = List.of("Lnet/minecraft/world/level/BlockAndTintGetter;",
            "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;");

    /** Owners old texture calls name: the base, and the vanilla textures mods call through. */
    static final List<String> TEXTURE_OWNERS = List.of(ABSTRACT_TEXTURE, "net/minecraft/client/renderer/texture/DynamicTexture",
            "net/minecraft/client/renderer/texture/SimpleTexture", "net/minecraft/client/renderer/texture/ReloadableTexture",
            "net/minecraft/client/renderer/texture/TextureAtlas");

    /** GameRenderer's static shader getters in 1.20.1 and 1.21.1, all removed in 1.21.2 (checked with javap on both). */
    static final List<String> SHADER_GETTERS = List.of("getPositionShader", "getPositionColorShader", "getPositionColorTexShader",
            "getPositionTexShader", "getPositionTexColorShader", "getParticleShader", "getPositionColorLightmapShader",
            "getPositionColorTexLightmapShader", "getPositionTexColorNormalShader", "getPositionTexLightmapColorShader",
            "getRendertypeSolidShader", "getRendertypeCutoutMippedShader", "getRendertypeCutoutShader",
            "getRendertypeTranslucentShader", "getRendertypeTranslucentMovingBlockShader",
            "getRendertypeTranslucentNoCrumblingShader", "getRendertypeArmorCutoutNoCullShader", "getRendertypeEntitySolidShader",
            "getRendertypeEntityCutoutShader", "getRendertypeEntityCutoutNoCullShader",
            "getRendertypeEntityCutoutNoCullZOffsetShader", "getRendertypeItemEntityTranslucentCullShader",
            "getRendertypeEntityTranslucentCullShader", "getRendertypeEntityTranslucentShader",
            "getRendertypeEntityTranslucentEmissiveShader", "getRendertypeEntitySmoothCutoutShader",
            "getRendertypeBeaconBeamShader", "getRendertypeEntityDecalShader", "getRendertypeEntityNoOutlineShader",
            "getRendertypeEntityShadowShader", "getRendertypeEntityAlphaShader", "getRendertypeEyesShader",
            "getRendertypeEnergySwirlShader", "getRendertypeLeashShader", "getRendertypeWaterMaskShader",
            "getRendertypeOutlineShader", "getRendertypeArmorGlintShader", "getRendertypeArmorEntityGlintShader",
            "getRendertypeGlintTranslucentShader", "getRendertypeGlintShader", "getRendertypeGlintDirectShader",
            "getRendertypeEntityGlintShader", "getRendertypeEntityGlintDirectShader", "getRendertypeTextShader",
            "getRendertypeTextBackgroundShader", "getRendertypeTextIntensityShader", "getRendertypeTextSeeThroughShader",
            "getRendertypeTextBackgroundSeeThroughShader", "getRendertypeTextIntensitySeeThroughShader",
            "getRendertypeLightningShader", "getRendertypeTripwireShader", "getRendertypeEndPortalShader",
            "getRendertypeEndGatewayShader", "getRendertypeLinesShader", "getRendertypeCrumblingShader",
            "getRendertypeGuiShader", "getRendertypeGuiOverlayShader", "getRendertypeGuiTextHighlightShader",
            "getRendertypeGuiGhostRecipeOverlayShader");

    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyRenderAccessBridge() {}

    /** Registers each bridge whose old member the host lacks; offline, the target version decides. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (lacks(host.apply(GAME_RENDERER), "getPositionTexShader", null, "1.21.2")) registerShaders(t);
        if (lacks(host.apply(RENDER_SYSTEM), "getModelViewStack", "()" + POSE_STACK, "1.20.5")) registerModelView(t);
        ClassNode minecraft = host.apply(MINECRAFT);
        ClassNode gameRenderer = host.apply(GAME_RENDERER);
        if (lacks(minecraft, "getMainRenderTarget", "()" + RENDER_TARGET, "26.2")
                && has(gameRenderer, "mainRenderTarget", "()" + RENDER_TARGET, "26.2")) {
            registerMainRenderTarget(t);
        }
        registerLevelRenderer(t, host.apply(LEVEL_RENDERER), host.apply(LEVEL_EXTRACTOR), host.apply(LIGHT_COORDS));
        ClassNode texture = host.apply(ABSTRACT_TEXTURE);
        if (lacks(texture, "setFilter", "(ZZ)V", "1.21.11") && has(texture, "getSampler", null, "26.1")) registerSetFilter(t);
        if (lacks(texture, "getId", "()I", "1.21.5") && has(texture, "getTexture", null, "1.21.5")) registerTextureId(t);
    }

    /** Every old shader getter answers null, and the old light setup on a shader does nothing. */
    static void registerShaders(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, SHADERS);
        for (String type : SHADER_TYPES) {
            String getter = "()L" + type + ";";
            // The method references in RenderSystem.setShader(GameRenderer::getPositionTexShader) link to the same helper,
            // whose Object result the lambda casts back.
            for (String name : SHADER_GETTERS) t.registerMethodRedirect(GAME_RENDERER, name, getter, SHADERS, "none", "()Ljava/lang/Object;");
            // Fabric's 1.21.2 VertexBuffer bridge may answer getShader already, and its answer is kept.
            if (!t.getMethodRedirects().containsKey(new RetromodTransformer.MethodKey(RENDER_SYSTEM, "getShader", getter))) {
                t.registerMethodRedirect(RENDER_SYSTEM, "getShader", getter, SHADERS, "none", "()Ljava/lang/Object;");
            }
            t.registerRemovedMethodNeutralize(RENDER_SYSTEM, "setupShaderLights", "(L" + type + ";)V");
        }
    }

    /** {@code getModelViewStack()} in its PoseStack form gets a stack that starts from the game's model-view matrix. */
    static void registerModelView(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, MODEL_VIEW);
        t.registerMethodRedirect(RENDER_SYSTEM, "getModelViewStack", "()" + POSE_STACK, MODEL_VIEW, "stack", "()" + POSE_STACK);
    }

    /** {@code Minecraft.getMainRenderTarget()} reads the game renderer's main target, where 26.2 moved it. */
    static void registerMainRenderTarget(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
        t.registerMethodRedirect(MINECRAFT, "getMainRenderTarget", "()" + RENDER_TARGET, LEVEL_CALLS, "mainRenderTarget",
                "(L" + MINECRAFT + ";)" + RENDER_TARGET, true);
    }

    /** LevelRenderer's light lookups, full rebuild, and section update, each to the place the host keeps it. */
    static void registerLevelRenderer(RetromodTransformer t, ClassNode levelRenderer, ClassNode extractor, ClassNode lightCoords) {
        // 26.1 keeps the light lookup on LevelRenderer under its new name; 26.2 moved it to LightCoordsUtil.
        String lightOwner = has(lightCoords, "getLightCoords", NEW_LIGHT, "26.2") ? LIGHT_COORDS
                : has(levelRenderer, "getLightCoords", NEW_LIGHT, "26.1") ? LEVEL_RENDERER : null;
        String withState = "(" + "Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;" + LIGHT_GETTER + BLOCK_STATE + BLOCK_POS + ")I";
        boolean stateLight = has(lightCoords, "getLightCoords", withState, "26.2");
        // A host that still has any getLightColor keeps both forms.
        boolean lightRemoved = lacks(levelRenderer, "getLightColor", null);
        for (String tint : TINT_GETTERS) {
            String old = "(" + tint + BLOCK_POS + ")I";
            if (lightOwner != null && lightRemoved) {
                t.registerMethodRedirect(LEVEL_RENDERER, "getLightColor", old, lightOwner, "getLightCoords", NEW_LIGHT);
            }
            String oldWithState = "(" + tint + BLOCK_STATE + BLOCK_POS + ")I";
            if (stateLight && lightRemoved) {
                RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
                t.registerMethodRedirect(LEVEL_RENDERER, "getLightColor", oldWithState, LEVEL_CALLS, "lightColor",
                        "(" + LIGHT_GETTER + BLOCK_STATE + BLOCK_POS + ")I");
            }
        }
        if (lacks(levelRenderer, "allChanged", "()V", "26.2") && has(extractor, "allChanged", "()V", "26.2")) {
            RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
            t.registerMethodRedirect(LEVEL_RENDERER, "allChanged", "()V", LEVEL_CALLS, "allChanged", "(L" + LEVEL_RENDERER + ";)V", true);
        }
        if (lacks(levelRenderer, "needsUpdate", "()V", "26.2") && has(levelRenderer, "sectionOcclusionGraph", null, "26.2")) {
            RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
            t.registerMethodRedirect(LEVEL_RENDERER, "needsUpdate", "()V", LEVEL_CALLS, "needsUpdate", "(L" + LEVEL_RENDERER + ";)V", true);
        }
    }

    /** {@code setFilter(blur, mipmap)} picks the host sampler with those filters. */
    static void registerSetFilter(RetromodTransformer t) {
        registerTextureCalls(t);
        for (String owner : TEXTURE_OWNERS) {
            t.registerInheritedMethodRedirect(owner, "setFilter", "(ZZ)V", TEXTURE_CALLS, "setFilter",
                    "(L" + ABSTRACT_TEXTURE + ";ZZ)V");
        }
    }

    /** {@code getId()} answers the OpenGL texture name. */
    static void registerTextureId(RetromodTransformer t) {
        registerTextureCalls(t);
        for (String owner : TEXTURE_OWNERS) {
            t.registerInheritedMethodRedirect(owner, "getId", "()I", TEXTURE_CALLS, "getId", "(L" + ABSTRACT_TEXTURE + ";)I");
        }
    }

    private static void registerTextureCalls(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, TEXTURE_CALLS);
        // The texture calls note a filter they could not apply through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
    }

    /** Whether the class lacks the method; a null descriptor matches any. Unreadable, hosts from {@code since} lack it. */
    static boolean lacks(ClassNode owner, String name, String desc, String since) {
        if (owner == null) return targetAtLeast(since);
        return lacks(owner, name, desc);
    }

    static boolean lacks(ClassNode owner, String name, String desc) {
        if (owner == null) return true;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) return false;
        }
        return true;
    }

    /** Whether the class declares the method; unreadable, hosts from {@code since} have it. */
    static boolean has(ClassNode owner, String name, String desc, String since) {
        if (owner == null) return targetAtLeast(since);
        return !lacks(owner, name, desc);
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
