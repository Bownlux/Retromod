/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/** Single render calls that kept otherwise clean 1.20.1 and 1.21.1 mods from linking on 26.x, each where the host lacks it. */
public final class LegacyRenderGapsBridge {

    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyRenderGaps";
    static final String ITEM_TRANSFORM = "net/minecraft/client/resources/model/cuboid/ItemTransform";
    static final String OLD_ITEM_TRANSFORM = "net/minecraft/client/renderer/block/model/ItemTransform";
    static final String OLD_APPLY = "(ZLcom/mojang/blaze3d/vertex/PoseStack;)V";
    static final String TEXTURES = "net/minecraft/client/renderer/texture/TextureManager";
    static final String DYNAMIC = "Lnet/minecraft/client/renderer/texture/DynamicTexture;";
    static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";
    static final String RENDER_TYPE = "net/minecraft/client/renderer/rendertype/RenderType";
    static final String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";
    static final String BY_ID = "(" + IDENTIFIER + ")L" + RENDER_TYPE + ";";
    static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    static final String POST_CHAIN = "net/minecraft/client/renderer/PostChain";
    static final String SHEETS = "net/minecraft/client/renderer/Sheets";
    static final String MONITOR = "com/mojang/blaze3d/platform/Monitor";
    static final String[] GL_STATE_MANAGERS = {"com/mojang/blaze3d/platform/GlStateManager", "com/mojang/blaze3d/opengl/GlStateManager",
            "com/mojang/renderpearl/backend/opengl/GlStateManager"};
    static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
    static final String BLOCK_ENTITY_RENDERER = "net/minecraft/client/renderer/blockentity/BlockEntityRenderer";
    static final String NATIVE_IMAGE = "com/mojang/blaze3d/platform/NativeImage";
    static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";

    private LegacyRenderGapsBridge() {}

    /** Registers each bridge whose old member the host lacks; the helper names 26.x classes, so it runs from the 26.1 shims. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // 1.21.5 applies an item transform to a pose instead of a whole stack.
        if (lacks(host.apply(ITEM_TRANSFORM), "apply", OLD_APPLY, "26.1")) {
            RebuiltApiClasses.registerTree(t, HELPER);
            String helperDesc = "(L" + ITEM_TRANSFORM + ";" + OLD_APPLY.substring(1);
            for (String owner : new String[]{ITEM_TRANSFORM, OLD_ITEM_TRANSFORM}) {
                t.registerMethodRedirect(owner, "apply", OLD_APPLY, HELPER, "apply", helperDesc, true);
            }
        }
        // 1.21.4 dropped the prefix form that made up a dynamic texture id.
        String oldRegister = "(Ljava/lang/String;" + DYNAMIC + ")" + IDENTIFIER;
        if (lacks(host.apply(TEXTURES), "register", oldRegister, "26.1")) {
            RebuiltApiClasses.registerTree(t, HELPER);
            String helperDesc = "(L" + TEXTURES + ";" + oldRegister.substring(1);
            for (String id : new String[]{IDENTIFIER, "Lnet/minecraft/resources/ResourceLocation;"}) {
                t.registerMethodRedirect(TEXTURES, "register", "(Ljava/lang/String;" + DYNAMIC + ")" + id, HELPER, "register", helperDesc, true);
            }
        }
        registerTranslucentCull(t, host);
        registerPostChains(t, host);
        registerSmallRenderCalls(t, host);
        registerSignSheets(t, host.apply(SHEETS));
        // 26.2 dropped the level renderer's tick counter.
        ClassNode levelRenderer = host.apply(LEVEL_RENDERER);
        if (levelRenderer == null ? targetAtLeast("26.2") : !hasField(levelRenderer, "ticks")) {
            RebuiltApiClasses.registerTree(t, HELPER);
            t.registerFieldRedirect(LEVEL_RENDERER, "ticks", "I", HELPER, "levelTicks", "(Ljava/lang/Object;)I");
        }
        // 1.21.5 moved blending into each render pipeline, so a global blend equation has nothing left to set.
        if (lacks(host.apply(RENDER_SYSTEM), "blendEquation", "(I)V", "26.1")) {
            t.registerRemovedMethodNeutralize(RENDER_SYSTEM, "blendEquation", "(I)V");
        }
    }

    /** The debug line strip, a picking refresh, the off-screen check with a block entity, and pixel blending. */
    static void registerSmallRenderCalls(RetromodTransformer t, Function<String, ClassNode> host) {
        // 1.21.11 kept only fixed-width lines; a strip of that width draws as plain lines.
        String strip = "(D)L" + RENDER_TYPE + ";";
        if (lacks(host.apply(RENDER_TYPE), "debugLineStrip", strip, "26.1")) {
            t.registerArgDropMethodRedirect(RENDER_TYPE, "debugLineStrip", strip, RENDER_TYPES, "lines", "()L" + RENDER_TYPE + ";");
        }
        // 26.1 made picking private to Minecraft, which refreshes it every frame anyway.
        if (lacks(host.apply(GAME_RENDERER), "pick", "(F)V", "26.1")) t.registerRemovedMethodNeutralize(GAME_RENDERER, "pick", "(F)V");
        String offScreen = "(Lnet/minecraft/world/level/block/entity/BlockEntity;)Z";
        if (lacks(host.apply(BLOCK_ENTITY_RENDERER), "shouldRenderOffScreen", offScreen, "26.1")) {
            RebuiltApiClasses.registerTree(t, HELPER);
            String helperDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
            t.registerMethodRedirect(BLOCK_ENTITY_RENDERER, "shouldRenderOffScreen", offScreen, HELPER, "shouldRenderOffScreen", helperDesc, true);
            t.registerSuperCallHelper(HELPER, "shouldRenderOffScreen", helperDesc, "shouldRenderOffScreenDefault");
        }
        // 1.21.5 dropped the bound framebuffer getter from every GL state manager name it has had since.
        for (String owner : GL_STATE_MANAGERS) {
            if (lacks(host.apply(owner), "getBoundFramebuffer", "()I", "26.1")) {
                RebuiltApiClasses.registerTree(t, HELPER);
                t.registerMethodRedirect(owner, "getBoundFramebuffer", "()I", HELPER, "getBoundFramebuffer", "()I");
            }
        }
        // The game thread is the game's own to set up; an old mod's call has nothing left to do.
        if (lacks(host.apply(RENDER_SYSTEM), "initGameThread", "(Z)V", "26.1")) {
            t.registerRemovedMethodNeutralize(RENDER_SYSTEM, "initGameThread", "(Z)V");
        }
        registerMonitor(t, host.apply(MONITOR));
        String blend = "(III)V";
        if (lacks(host.apply(NATIVE_IMAGE), "blendPixel", blend, "26.1")) {
            RebuiltApiClasses.registerTree(t, HELPER);
            t.registerMethodRedirect(NATIVE_IMAGE, "blendPixel", blend, HELPER, "blendPixel", "(L" + NATIVE_IMAGE + ";III)V", true);
        }
    }

    /** 26.2 dropped the sign atlas and its texture maps; old code reads and fills stand-ins nothing draws from. */
    static void registerSignSheets(RetromodTransformer t, ClassNode sheets) {
        // 26.1 still has the maps under their sprite names, which LegacyScatteredMembersBridge renames to.
        boolean gone = sheets == null ? targetAtLeast("26.2") : !hasField(sheets, "SIGN_SHEET") && !hasField(sheets, "SIGN_SPRITES");
        if (!gone) return;
        RebuiltApiClasses.registerTree(t, HELPER);
        t.registerFieldRedirect(SHEETS, "SIGN_SHEET", null, HELPER, "signSheet", "()" + IDENTIFIER);
        t.registerFieldRedirect(SHEETS, "SIGN_MATERIALS", null, HELPER, "signMaterials", "()Ljava/util/Map;");
        t.registerFieldRedirect(SHEETS, "HANGING_SIGN_MATERIALS", null, HELPER, "hangingSignMaterials", "()Ljava/util/Map;");
    }

    private static boolean hasField(ClassNode owner, String name) {
        return owner.fields.stream().anyMatch(field -> field.name.equals(name));
    }

    /** 26.2 made Monitor a record whose getters lost their get prefix; 26.3 also dropped the GLFW handle, which stays unbridged. */
    static void registerMonitor(RetromodTransformer t, ClassNode monitor) {
        String[][] renames = {{"getMonitor", "monitor", "()J"}, {"getX", "x", "()I"}, {"getY", "y", "()I"}, {"getModeCount", "modeCount", "()I"},
                {"getCurrentMode", "currentMode", "()Lcom/mojang/blaze3d/platform/VideoMode;"},
                {"getMode", "mode", "(I)Lcom/mojang/blaze3d/platform/VideoMode;"}};
        for (String[] rename : renames) {
            // Offline, 26.2 still has the handle under monitor(), which 26.3 dropped.
            boolean renamed = monitor == null ? targetAtLeast("26.2") && (!rename[0].equals("getMonitor") || !targetAtLeast("26.3"))
                    : !has(monitor, rename[0], rename[2]) && has(monitor, rename[1], rename[2]);
            if (renamed) t.registerMethodRedirect(MONITOR, rename[0], rename[2], MONITOR, rename[1], rename[2]);
        }
    }

    /** 1.21.2 rebuilt post chains from effect files and moved their drawing into the frame graph; old chains are never built. */
    static void registerPostChains(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode chain = host.apply(POST_CHAIN);
        if (!lacks(chain, "process", "(F)V", "26.1")) return;
        RebuiltApiClasses.registerTree(t, HELPER);
        // Nothing to draw, resize or feed, so the old calls are dropped.
        for (String[] call : new String[][]{{"process", "(F)V"}, {"resize", "(II)V"}, {"setUniform", "(Ljava/lang/String;F)V"}}) {
            if (lacks(chain, call[0], call[1], "26.1")) t.registerRemovedMethodNeutralize(POST_CHAIN, call[0], call[1]);
        }
        t.registerMethodRedirect(POST_CHAIN, "getName", "()Ljava/lang/String;", HELPER, "postChainName", "(Ljava/lang/Object;)Ljava/lang/String;", true);
        String factory = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)L" + POST_CHAIN + ";";
        for (String id : new String[]{IDENTIFIER, "Lnet/minecraft/resources/ResourceLocation;"}) {
            t.registerConstructorRedirect(POST_CHAIN, "(L" + TEXTURES + ";Lnet/minecraft/server/packs/resources/ResourceProvider;"
                    + "Lcom/mojang/blaze3d/pipeline/RenderTarget;" + id + ")V", HELPER, "postChain", factory);
        }
        if (lacks(host.apply(GAME_RENDERER), "currentEffect", "()L" + POST_CHAIN + ";", "26.1")) {
            t.registerMethodRedirect(GAME_RENDERER, "currentEffect", "()L" + POST_CHAIN + ";", HELPER, "currentEffect",
                    "(Ljava/lang/Object;)L" + POST_CHAIN + ";", true);
        }
    }

    /** 1.21.2 dropped the culled translucent entity type and 26.3 brought it back on RenderTypes; until then the unculled one is closest. */
    static void registerTranslucentCull(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!lacks(host.apply(RENDER_TYPE), "entityTranslucentCull", BY_ID, "26.1")) return;
        ClassNode types = host.apply(RENDER_TYPES);
        boolean hostCulls = types == null ? targetAtLeast("26.3") : has(types, "entityTranslucentCull", BY_ID);
        t.registerMethodRedirect(RENDER_TYPE, "entityTranslucentCull", BY_ID, RENDER_TYPES,
                hostCulls ? "entityTranslucentCull" : "entityTranslucent", BY_ID);
    }

    private static boolean lacks(ClassNode owner, String name, String desc, String since) {
        return owner == null ? targetAtLeast(since) : !has(owner, name, desc);
    }

    private static boolean has(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
