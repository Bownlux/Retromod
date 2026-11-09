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
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Rebuilds the draw calls 26.1 removed (ItemRenderer, the dispatcher's render) and the helpers old block entity and entity renderers draw through. */
public final class LegacyItemDrawBridge {

    public static final String OLD_ITEM_RENDERER = "net/minecraft/client/renderer/entity/ItemRenderer";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String BLOCK_ENTITY_CONTEXT = "net/minecraft/client/renderer/blockentity/BlockEntityRendererProvider$Context";
    static final String ENTITY_CONTEXT = "net/minecraft/client/renderer/entity/EntityRendererProvider$Context";
    static final String DISPATCHER = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    static final String BLOCK_ENTITY_RENDERER = LegacyBlockEntityRendererAdapter.RENDERER;
    static final String ITEM_FEATURE_RENDERER = "net/minecraft/client/renderer/feature/ItemFeatureRenderer";
    static final String ENTITY_RENDERER = "net/minecraft/client/renderer/entity/EntityRenderer";
    static final String OLD_BUFFERS = HostBuffers.HOST_BUFFERS;
    public static final String ITEMS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyItemStackRenderer";
    public static final String ENTITIES = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyEntityDraws";
    static final String GETTER_DESC = "()L" + OLD_ITEM_RENDERER + ";";
    static final String HELPER_GETTER_DESC = "(Ljava/lang/Object;)L" + ITEMS + ";";
    static final String OLD_RENDER_DESC = "(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;L" + OLD_BUFFERS + ";I)V";
    static final String SHADOW_DESC = "(Z)V";
    // The first Minecraft without ItemRenderer, where every old item draw needs this bridge.
    static final String FIRST_HOST = "26.1";

    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyItemDrawBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostDroppedItemRenderer(host)) return;
        Remapper names = HostBuffers.names(host);
        RebuiltApiClasses.registerTree(t, ITEMS, names);
        RebuiltApiClasses.registerTree(t, ENTITIES, names);
        // The rebuilt draws note what they skipped through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);

        // Class redirect first, so the getters below also match calls once the type is renamed. The host has no class
        // under the old name, so no mod built for it can name one.
        t.registerClassRedirect(OLD_ITEM_RENDERER, ITEMS);
        // Mods keep the renderer from their constructors on, so each getter answers the one stand-in at any time.
        redirectGetter(t, host, MINECRAFT);
        redirectGetter(t, host, BLOCK_ENTITY_CONTEXT);
        redirectGetter(t, host, ENTITY_CONTEXT);
        // The models the item renderer hands out and takes, and the block renderer old code reads block models through.
        LegacyBakedItemBridge.register(t, host, names);
        // The glint textures' ids moved to the item feature renderer, as Create and Punchy read them.
        redirectGlintIds(t, host);

        // The dispatcher's old entity draw and its shadow switch, for old renderers that draw an entity of their own.
        ClassNode dispatcher = host.apply(DISPATCHER);
        if (dispatcher == null || !declares(dispatcher, "render", OLD_RENDER_DESC)) {
            t.registerMethodRedirect(DISPATCHER, "render", OLD_RENDER_DESC, ENTITIES, "render", "(L" + DISPATCHER + ";"
                    + OLD_RENDER_DESC.substring(1), true);
        }
        if (dispatcher == null || !declares(dispatcher, "setRenderShadow", SHADOW_DESC)) {
            t.registerMethodRedirect(DISPATCHER, "setRenderShadow", SHADOW_DESC, ENTITIES, "setRenderShadow", "(Ljava/lang/Object;Z)V", true);
        }

        // With these helpers registered, the block entity and entity renderer adapters give old renderers a submit that draws.
        if (hostSubmits(host, BLOCK_ENTITY_RENDERER, LegacyBlockEntityRendererAdapter.SUBMIT_DESC)) {
            RebuiltApiClasses.registerTree(t, LegacyBlockEntityRendererAdapter.HELPER, names);
        }
        if (hostSubmits(host, ENTITY_RENDERER, LegacyEntityRendererAdapter.SUBMIT_DESC)) {
            RebuiltApiClasses.registerTree(t, LegacyEntityRendererAdapter.HELPER, names);
        }
    }

    /** Whether the host's renderer base draws by submitting a render state; a server or an offline run reads neither. */
    static boolean hostSubmits(Function<String, ClassNode> host, String base, String submitDesc) {
        ClassNode node = host.apply(base);
        return node == null || declares(node, "submit", submitDesc);
    }

    /** Whether the host lost ItemRenderer; a server or an offline run answers by the target, which lost it in 26.1. */
    public static boolean hostDroppedItemRenderer(Function<String, ClassNode> host) {
        // Every 26.x client has the item model API, so reading it tells a client host from a server or an offline run.
        if (host.apply(HostBuffers.CLIENT_ANCHOR) != null) return host.apply(OLD_ITEM_RENDERER) == null;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
    }

    /** A mixin target that is the removed ItemRenderer, which the redirect would land on the mod's own rebuilt copy; null for none. */
    public static String removedMixinTarget(List<String> targets, UnaryOperator<String> redirectForCurrentMod) {
        // Minecraft 26.x draws no item through that copy, so a mixin into it could only fail or do nothing. A Fabric mixin
        // still names the class by intermediary here, which reaches the copy through the Mojang name, so the chain is followed.
        for (String target : targets) {
            if (target.startsWith("net/minecraft/") && endOfRedirects(target, redirectForCurrentMod).equals(ITEMS)) return target;
        }
        return null;
    }

    private static String endOfRedirects(String name, UnaryOperator<String> redirectForCurrentMod) {
        String current = name;
        for (int hop = 0; hop < 8; hop++) {
            String next = redirectForCurrentMod.apply(current);
            if (next.equals(current)) break;
            current = next;
        }
        return current;
    }

    private static void redirectGlintIds(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode feature = host.apply(ITEM_FEATURE_RENDERER);
        if (feature != null && feature.fields.stream().noneMatch(f -> f.name.equals("ENCHANTED_GLINT_ITEM"))) return;
        // Minecraft 1.20.1 called the armor glint the entity glint.
        String[][] ids = {{"ENCHANTED_GLINT_ITEM", "ENCHANTED_GLINT_ITEM"}, {"ENCHANTED_GLINT_ARMOR", "ENCHANTED_GLINT_ARMOR"},
            {"ENCHANTED_GLINT_ENTITY", "ENCHANTED_GLINT_ARMOR"}};
        for (String[] id : ids) {
            // Keyed on the old owner and on the stand-in, whichever name the field read carries when it is looked up.
            for (String owner : List.of(OLD_ITEM_RENDERER, ITEMS)) t.registerFieldRedirect(owner, id[0], ITEM_FEATURE_RENDERER, id[1]);
        }
    }

    private static void redirectGetter(RetromodTransformer t, Function<String, ClassNode> host, String owner) {
        ClassNode node = host.apply(owner);
        if (node != null && node.methods.stream().anyMatch(m -> m.name.equals("getItemRenderer"))) return;
        t.registerMethodRedirect(owner, "getItemRenderer", GETTER_DESC, ITEMS, "get", HELPER_GETTER_DESC, true);
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }
}
