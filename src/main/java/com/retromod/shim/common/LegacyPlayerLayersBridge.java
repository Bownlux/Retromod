/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.util.McReflect;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Lets old mods add layers to the player renderers: getSkinMap() and the renderer map as views, and the helper old layers draw through. */
public final class LegacyPlayerLayersBridge {

    static final String DISPATCHER = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    static final String RENDER_LAYER = "net/minecraft/client/renderer/entity/layers/RenderLayer";
    static final String SUBMIT_DESC = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
            + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V";
    static final String GET_RENDERER_DESC = "(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)"
            + "Lnet/minecraft/client/renderer/entity/EntityRenderer;";
    public static final String SKIN_MAP = RebuiltApiClasses.ROOT + "minecraft/client/renderer/entity/LegacySkinMap";
    public static final String RENDERER_MAP = RebuiltApiClasses.ROOT + "minecraft/client/renderer/entity/LegacyEntityRendererMap";
    public static final String LAYERS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyRenderLayers";

    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyPlayerLayersBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (hostDroppedSkinMap(host)) {
            RebuiltApiClasses.registerTree(t, SKIN_MAP);
            // Sophisticated Backpacks and Create add their layers through it in a reload listener, so a missing one stops the reload.
            t.registerMethodRedirect(DISPATCHER, "getSkinMap", "()Ljava/util/Map;",
                    SKIN_MAP, "of", "(L" + DISPATCHER + ";)Ljava/util/Map;", true);
        }
        if (hostHidesRenderers(host)) {
            RebuiltApiClasses.registerTree(t, RENDERER_MAP);
            // The same mods then add a layer to every living renderer through the map 1.20.1 left public.
            t.registerFieldStaticBridge(DISPATCHER, "renderers", RENDERER_MAP, "of", "(L" + DISPATCHER + ";)Ljava/util/Map;", null, null);
        }
        if (hostLayersSubmitStates(host)) {
            RebuiltApiClasses.registerTree(t, LAYERS, HostBuffers.names(host));
            // The rebuilt layer helper notes a layer that threw through the game log.
            SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
            // With the helper registered, LegacyRenderLayerAdapter gives each old layer a submit that calls it.
        }
    }

    /** Whether the host lost getSkinMap() and picks player renderers by state; a server or offline run reads neither. */
    static boolean hostDroppedSkinMap(Function<String, ClassNode> host) {
        ClassNode dispatcher = host.apply(DISPATCHER);
        if (dispatcher == null) return true;
        return !declares(dispatcher, "getSkinMap", "()Ljava/util/Map;") && declares(dispatcher, "getRenderer", GET_RENDERER_DESC);
    }

    /** Whether the host keeps the entity renderer map private, as vanilla and NeoForge do and Forge does not. */
    static boolean hostHidesRenderers(Function<String, ClassNode> host) {
        ClassNode dispatcher = host.apply(DISPATCHER);
        // Forge 26.2 and 26.3 make it public, so offline a Forge target keeps the direct read a running Forge game takes.
        if (dispatcher == null) return !McReflect.isForgeTarget();
        return dispatcher.fields.stream().anyMatch(f -> f.name.equals("renderers") && (f.access & Opcodes.ACC_PUBLIC) == 0);
    }

    /** Whether the host's layers submit a render state, not draw an entity; a server or offline run reads neither. */
    static boolean hostLayersSubmitStates(Function<String, ClassNode> host) {
        ClassNode layer = host.apply(RENDER_LAYER);
        return layer == null || declares(layer, "submit", SUBMIT_DESC);
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }
}
