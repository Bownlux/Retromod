/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.polyfill.minecraft.RetroItemBlockRenderTypes;
import com.retromod.shim.common.HostBuffers;
import com.retromod.shim.common.LegacyBakedItemBridge;
import com.retromod.shim.common.LegacyItemDrawBridge;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.forge.LegacyForgeModelBridge;
import com.retromod.util.McReflect;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Rebuilds NeoForge 1.21.1's and Forge 1.20.1's dynamic model API on NeoForge 26.x, where an old BakedModel is Minecraft's block model. */
public final class LegacyNeoForgeBakedModelBridge {

    private static final String NEO = "net/neoforged/neoforge/";
    private static final String FORGE = "net/minecraftforge/";
    static final String OLD_DYNAMIC = NEO + "client/model/IDynamicBakedModel";
    static final String OLD_WRAPPER = NEO + "client/model/BakedModelWrapper";
    static final String OLD_EXTENSION = NEO + "client/extensions/IBakedModelExtension";
    static final String OLD_SET = NEO + "client/ChunkRenderTypeSet";
    static final String OLD_GEOMETRY_EVENT = NEO + "client/event/ModelEvent$RegisterGeometryLoaders";
    static final String OLD_GEOMETRY_LOADER = NEO + "client/model/geometry/IGeometryLoader";
    static final String FORGE_DYNAMIC = FORGE + "client/model/IDynamicBakedModel";
    static final String FORGE_WRAPPER = FORGE + "client/model/BakedModelWrapper";
    static final String FORGE_EXTENSION = FORGE + "client/extensions/IForgeBakedModel";
    static final String FORGE_SET = FORGE + "client/ChunkRenderTypeSet";
    static final String FORGE_GEOMETRY_EVENT = FORGE + "client/event/ModelEvent$RegisterGeometryLoaders";
    static final String FORGE_GEOMETRY_LOADER = FORGE + "client/model/geometry/IGeometryLoader";
    static final String MODEL_DATA = NEO + "model/data/ModelData";
    static final String BLOCK_STATE_MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
    static final String ITEM_BLOCK_RENDER_TYPES = "net/minecraft/client/renderer/ItemBlockRenderTypes";
    // Every NeoForge host ships this class, so reading it tells a running NeoForge host from an offline run.
    static final String ANCHOR = NEO + "common/NeoForge";

    static final String ROOT = RebuiltApiClasses.ROOT + "neoforge/client/model/";
    public static final String DYNAMIC = ROOT + "IDynamicBakedModel";
    public static final String WRAPPER = ROOT + "BakedModelWrapper";
    public static final String CALLS = ROOT + "LegacyModelDataCalls";
    public static final String GEOMETRY_EVENT = RebuiltApiClasses.ROOT + "neoforge/client/event/LegacyRegisterGeometryLoaders";
    static final String SET = LegacyForgeModelBridge.CHUNK_RENDER_TYPE_SET;
    static final String LAYERS = LegacyForgeModelBridge.RENDER_LAYERS;
    private static final String POLYFILL = "com/retromod/polyfill/minecraft/RetroItemBlockRenderTypes";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    /** The first NeoForge host whose old BakedModel is Minecraft's block model, which the rebuilt types extend. */
    static final String FIRST_HOST = "26.1";
    // Minecraft 1.21.5 replaced BakedModel with BlockStateModel, so only older mods call these members on it.
    static final String FIRST_WITHOUT_BAKED_MODEL = "1.21.5";

    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String RENDER_TYPE = "Lnet/minecraft/client/renderer/rendertype/RenderType;";
    private static final String DATA = "L" + MODEL_DATA + ";";

    /** Each removed class an old mod names, and the class rebuilt or kept in its place. */
    static final Map<String, String> REBUILT = rebuilt();

    /** Forge 1.20.1's model data classes, and NeoForge's same classes with the same members. */
    static final Map<String, String> FORGE_MODEL_DATA = Map.of(
            FORGE + "client/model/data/ModelData", MODEL_DATA,
            FORGE + "client/model/data/ModelData$Builder", MODEL_DATA + "$Builder",
            FORGE + "client/model/data/ModelProperty", NEO + "model/data/ModelProperty");

    private LegacyNeoForgeBakedModelBridge() {}

    private static Map<String, String> rebuilt() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(OLD_DYNAMIC, DYNAMIC);
        names.put(FORGE_DYNAMIC, DYNAMIC);
        names.put(OLD_WRAPPER, WRAPPER);
        names.put(FORGE_WRAPPER, WRAPPER);
        // The old model's extension members belong to the model, which old code holds as Minecraft's block model.
        names.put(OLD_EXTENSION, BLOCK_STATE_MODEL);
        names.put(FORGE_EXTENSION, BLOCK_STATE_MODEL);
        names.put(OLD_SET, SET);
        names.put(FORGE_SET, SET);
        // A subscriber class that names a missing event loses every listener it has, so the old name must resolve.
        names.put(OLD_GEOMETRY_EVENT, GEOMETRY_EVENT);
        names.put(FORGE_GEOMETRY_EVENT, GEOMETRY_EVENT);
        return names;
    }

    /** Registers the bridge where the NeoForge host has no old model API; offline, a NeoForge target from 26.1. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    /** The same, reading the host's classes through {@code host}. */
    public static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!McReflect.isNeoForge() || !hostTakesBridge(host)) return;
        // The same rewrite the item draw bridge gives its classes, so the shared helpers match whichever registers first.
        Remapper names = HostBuffers.names(host);
        for (String rebuilt : List.of(DYNAMIC, WRAPPER, CALLS, GEOMETRY_EVENT, SET, LAYERS)) {
            RebuiltApiClasses.registerTree(t, rebuilt, names);
        }
        SyntheticEmbedder.registerClassResource(t, POLYFILL, RetroItemBlockRenderTypes.class);
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);

        // Class redirects first, so the member redirects below also match the calls once the classes are renamed. The
        // host has none of the old names, so no mod built for it can name one.
        REBUILT.forEach(t::registerClassRedirect);
        FORGE_MODEL_DATA.forEach(t::registerClassRedirect);
        registerModelDataCalls(t);
        registerRenderLayers(t);
        registerGeometryEvent(t);
    }

    /** Whether the host is NeoForge 26.x without the old model API, where the item draw bridge makes old BakedModel the block model. */
    static boolean hostTakesBridge(Function<String, ClassNode> host) {
        // Offline the CLI reads only the vanilla jar, so the target version stands in for the host.
        if (host.apply(ANCHOR) == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
        if (host.apply(OLD_DYNAMIC) != null || host.apply(MODEL_DATA) == null) return false;
        return LegacyItemDrawBridge.hostDroppedItemRenderer(host);
    }

    // NeoForge's model data members of the old BakedModel, and Forge's occlusion members, on what it became.
    private static void registerModelDataCalls(RetromodTransformer t) {
        String model = "L" + BLOCK_STATE_MODEL + ";";
        String set = "L" + SET + ";";
        String[][] members = {
            {"getQuads", "(" + STATE + "Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;" + DATA + RENDER_TYPE + ")Ljava/util/List;"},
            {"getRenderTypes", "(" + STATE + "Lnet/minecraft/util/RandomSource;" + DATA + ")" + set},
            {"getParticleIcon", "(" + DATA + ")Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"},
            {"getModelData", "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;" + STATE + DATA + ")"
                    + DATA},
            {"useAmbientOcclusion", "(" + STATE + DATA + RENDER_TYPE + ")Lnet/minecraft/util/TriState;"},
            {"useAmbientOcclusion", "(" + STATE + ")Z"},
            {"useAmbientOcclusion", "(" + STATE + RENDER_TYPE + ")Z"},
        };
        for (String[] member : members) {
            String helperDesc = "(" + model + member[1].substring(1);
            // Keyed on every spelling the set can have when the call is matched: rebuilt, NeoForge's or Forge's.
            for (String spelling : spellings(member[1])) {
                t.registerMethodRedirect(BLOCK_STATE_MODEL, member[0], spelling, CALLS, member[0], helperDesc, true);
                t.limitRedirectToModsBefore(BLOCK_STATE_MODEL, member[0], spelling, FIRST_WITHOUT_BAKED_MODEL);
            }
            // Each member had a default body, which an override reaches through super without running itself again.
            t.registerSuperCallHelper(CALLS, member[0], helperDesc, LegacyBakedItemBridge.superName(member[0]));
        }
    }

    // NeoForge 1.21.1's ItemBlockRenderTypes reader and setter of render type sets, which 26.1 removed with the class.
    private static void registerRenderLayers(RetromodTransformer t) {
        String set = "L" + SET + ";";
        String block = "Lnet/minecraft/world/level/block/Block;";
        for (String spelling : spellings(set)) {
            t.registerMethodRedirect(ITEM_BLOCK_RENDER_TYPES, "getRenderLayers", "(" + STATE + ")" + spelling, LAYERS, "getRenderLayers",
                    "(" + STATE + ")" + set);
            t.registerMethodRedirect(ITEM_BLOCK_RENDER_TYPES, "setRenderLayer", "(" + block + spelling + ")V", LAYERS, "setRenderLayer",
                    "(" + block + set + ")V");
        }
    }

    // The old register calls name the removed loader type; the stand-in takes the loader as an Object, as it bakes nothing.
    private static void registerGeometryEvent(RetromodTransformer t) {
        for (String owner : List.of(OLD_GEOMETRY_EVENT, FORGE_GEOMETRY_EVENT, GEOMETRY_EVENT)) {
            for (String loader : List.of(OLD_GEOMETRY_LOADER, FORGE_GEOMETRY_LOADER)) {
                t.registerMethodRedirect(owner, "register", "(Lnet/minecraft/resources/Identifier;L" + loader + ";)V", GEOMETRY_EVENT,
                        "register", "(Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V");
                t.registerMethodRedirect(owner, "register", "(Ljava/lang/String;L" + loader + ";)V", GEOMETRY_EVENT, "register",
                        "(Ljava/lang/String;Ljava/lang/Object;)V");
            }
        }
    }

    private static List<String> spellings(String desc) {
        String set = "L" + SET + ";";
        return List.of(desc, desc.replace(set, "L" + OLD_SET + ";"), desc.replace(set, "L" + FORGE_SET + ";")).stream().distinct().toList();
    }

    /**
     * A mixin target this bridge rebuilt inside each mod or pointed at Minecraft's block model; null for none. Such a mixin
     * would land on the mod's own copy, or inject old members into Minecraft's model, so it is dropped.
     */
    public static String removedMixinTarget(List<String> targets, UnaryOperator<String> redirectForCurrentMod) {
        // Only NeoForge registers these redirects; Forge's own model bridge keeps its rule for Forge's names.
        if (!McReflect.isNeoForge()) return null;
        for (String target : targets) {
            if (REBUILT.containsKey(target) && !redirectForCurrentMod.apply(target).equals(target)) return target;
        }
        return null;
    }
}
