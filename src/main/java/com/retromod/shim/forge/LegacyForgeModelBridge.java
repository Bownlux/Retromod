/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.polyfill.minecraft.RetroItemBlockRenderTypes;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Rebuilds Forge 1.20.1's render type sets, render type groups and int[] quad API inside each old mod, on Forge 26.2 and newer. */
public final class LegacyForgeModelBridge {

    static final String OLD_CHUNK_RENDER_TYPE_SET = "net/minecraftforge/client/ChunkRenderTypeSet";
    static final String OLD_QUAD_TRANSFORMER = "net/minecraftforge/client/model/IQuadTransformer";
    static final String OLD_QUAD_TRANSFORMERS = "net/minecraftforge/client/model/QuadTransformers";
    static final String RENDER_TYPE_GROUP = "net/minecraftforge/client/RenderTypeGroup";
    static final String ITEM_BLOCK_RENDER_TYPES = "net/minecraft/client/renderer/ItemBlockRenderTypes";
    static final String QUAD = "net/minecraft/client/resources/model/geometry/BakedQuad";
    static final String OLD_QUAD = "net/minecraft/client/renderer/block/model/BakedQuad";

    static final String ROOT = RebuiltApiClasses.ROOT + "forge/client/model/";
    // The render type set and its layer table hold no Forge type, so NeoForge's model bridge rebuilds them too.
    public static final String CHUNK_RENDER_TYPE_SET = RebuiltApiClasses.ROOT + "minecraft/client/model/ChunkRenderTypeSet";
    public static final String RENDER_LAYERS = RebuiltApiClasses.ROOT + "minecraft/client/model/LegacyChunkLayers";
    static final String QUAD_TRANSFORMER = ROOT + "LegacyQuadTransformer";
    static final String QUAD_TRANSFORMERS = ROOT + "LegacyQuadTransformers";
    static final String QUAD_VERTICES = ROOT + "LegacyQuadVertices";
    static final String QUAD_MATERIALS = ROOT + "LegacyQuadMaterials";
    static final String RENDER_TYPE_GROUPS = ROOT + "LegacyRenderTypeGroups";

    // Minecraft 26.1 replaced the quad's int[] with positions and packed UVs, so a mod built for 26.1 or newer
    // already uses Forge's new quad transformers under the old names.
    static final String FIRST_QUAD_RECORD_VERSION = "26.1";

    private static final String RENDER_TYPE = "Lnet/minecraft/client/renderer/rendertype/RenderType;";
    private static final String OLD_RENDER_TYPE = "Lnet/minecraft/client/renderer/RenderType;";
    private static final String BLOCK = "Lnet/minecraft/world/level/block/Block;";
    private static final String BLOCK_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String DIRECTION = "Lnet/minecraft/core/Direction;";
    private static final String SPRITE = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;";
    private static final String POLYFILL = "com/retromod/polyfill/minecraft/RetroItemBlockRenderTypes";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyForgeModelBridge() {}

    /** Registers the rebuilt classes where the Forge host has RenderTypeGroup and no ChunkRenderTypeSet; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // Forge 26.2 dropped ChunkRenderTypeSet and gave RenderTypeGroup a chunk layer; a host with the set needs nothing.
        if (!ForgeHostShape.matches(List.of(RENDER_TYPE_GROUP), List.of(OLD_CHUNK_RENDER_TYPE_SET),
                ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            return;
        }
        for (String rebuilt : List.of(CHUNK_RENDER_TYPE_SET, QUAD_TRANSFORMER, QUAD_TRANSFORMERS, QUAD_VERTICES,
                RENDER_LAYERS, RENDER_TYPE_GROUPS)) {
            RebuiltApiClasses.registerTree(t, rebuilt);
        }
        // The layer table every copy shares lives in the common polyfill, which old setRenderLayer calls reach.
        SyntheticEmbedder.registerClassResource(t, POLYFILL, RetroItemBlockRenderTypes.class);
        // The quad conversion notes the vertex colors it drops through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);

        registerRenderTypeSets(t);
        registerQuads(t);
        // The old BakedModel family draws through the render type sets and quads above.
        LegacyForgeBakedModelBridge.register(t, host);
        // Old geometry loaders bake into that BakedModel family.
        LegacyForgeGeometryBridge.register(t, host);
    }

    private static void registerRenderTypeSets(RetromodTransformer t) {
        t.registerClassRedirect(OLD_CHUNK_RENDER_TYPE_SET, CHUNK_RENDER_TYPE_SET);
        t.limitClassRedirectToModsBefore(OLD_CHUNK_RENDER_TYPE_SET, ForgeHostShape.FIRST_CHECKED_VERSION);

        // The Forge-only reader and setter; setRenderLayer with a render type or a predicate already reaches the
        // common polyfill, which records it in the same table.
        String oldSet = "Lnet/minecraftforge/client/ChunkRenderTypeSet;";
        String set = "L" + CHUNK_RENDER_TYPE_SET + ";";
        method(t, ITEM_BLOCK_RENDER_TYPES, "getRenderLayers", "(" + BLOCK_STATE + ")" + oldSet,
                RENDER_LAYERS, "getRenderLayers", "(" + BLOCK_STATE + ")" + set, false, ForgeHostShape.FIRST_CHECKED_VERSION);
        method(t, ITEM_BLOCK_RENDER_TYPES, "setRenderLayer", "(" + BLOCK + oldSet + ")V",
                RENDER_LAYERS, "setRenderLayer", "(" + BLOCK + set + ")V", false, ForgeHostShape.FIRST_CHECKED_VERSION);

        // RenderTypeGroup's block member became a chunk layer; its old constructors and getter map the layer's render type.
        String group = "L" + RENDER_TYPE_GROUP + ";";
        for (String renderType : List.of(RENDER_TYPE, OLD_RENDER_TYPE)) {
            t.registerConstructorRedirect(RENDER_TYPE_GROUP, "(" + renderType + renderType + ")V",
                    RENDER_TYPE_GROUPS, "create", "(" + RENDER_TYPE + RENDER_TYPE + ")" + group);
            t.registerConstructorRedirect(RENDER_TYPE_GROUP, "(" + renderType + renderType + renderType + ")V",
                    RENDER_TYPE_GROUPS, "create", "(" + RENDER_TYPE + RENDER_TYPE + RENDER_TYPE + ")" + group);
            method(t, RENDER_TYPE_GROUP, "block", "()" + renderType,
                    RENDER_TYPE_GROUPS, "block", "(" + group + ")" + RENDER_TYPE, true, ForgeHostShape.FIRST_CHECKED_VERSION);
        }
    }

    private static void registerQuads(RetromodTransformer t) {
        // Forge 26.2 kept these names for quad transformers that return a new quad, so only older mods move.
        t.registerClassRedirect(OLD_QUAD_TRANSFORMER, QUAD_TRANSFORMER);
        t.limitClassRedirectToModsBefore(OLD_QUAD_TRANSFORMER, FIRST_QUAD_RECORD_VERSION);
        t.registerClassRedirect(OLD_QUAD_TRANSFORMERS, QUAD_TRANSFORMERS);
        t.limitClassRedirectToModsBefore(OLD_QUAD_TRANSFORMERS, FIRST_QUAD_RECORD_VERSION);

        // The class move to geometry/BakedQuad may run before or after these, so both owners are keyed.
        String quad = "L" + QUAD + ";";
        for (String owner : List.of(QUAD, OLD_QUAD)) {
            for (String tail : List.of("Z", "ZZ")) {
                String head = "([II" + DIRECTION + SPRITE;
                t.registerConstructorRedirect(owner, head + tail + ")V", QUAD_VERTICES, "create", head + tail + ")" + quad);
            }
            String[][] getters = {
                {"getVertices", "[I"},
                {"getTintIndex", "I"},
                {"getSprite", SPRITE},
                {"isShade", "Z"},
                {"hasAmbientOcclusion", "Z"},
            };
            for (String[] getter : getters) {
                method(t, owner, getter[0], "()" + getter[1], QUAD_VERTICES, getter[0], "(" + quad + ")" + getter[1],
                        true, FIRST_QUAD_RECORD_VERSION);
            }
            // The record kept the direction under its own name.
            method(t, owner, "getDirection", "()" + DIRECTION, QUAD, "direction", "()" + DIRECTION, false,
                    FIRST_QUAD_RECORD_VERSION);
        }
    }

    private static void method(RetromodTransformer t, String owner, String name, String desc, String newOwner,
            String newName, String newDesc, boolean takesReceiver, String firstVersionWithout) {
        if (takesReceiver) {
            t.registerMethodRedirect(owner, name, desc, newOwner, newName, newDesc, true);
        } else {
            t.registerMethodRedirect(owner, name, desc, newOwner, newName, newDesc);
        }
        // A mod built for a host that has the member again keeps it.
        t.limitRedirectToModsBefore(owner, name, desc, firstVersionWithout);
    }
}
