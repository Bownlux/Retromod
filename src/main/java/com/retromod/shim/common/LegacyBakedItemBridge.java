/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Rebuilds the model side of old item and block drawing that 26.1 removed: BakedModel calls, the model shapers and BlockRenderDispatcher. */
public final class LegacyBakedItemBridge {

    static final String OLD_BAKED_MODEL = "net/minecraft/client/resources/model/BakedModel";
    static final String BLOCK_STATE_MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
    static final String OLD_ITEM_OVERRIDES = "net/minecraft/client/renderer/block/model/ItemOverrides";
    public static final String OLD_ITEM_SHAPER = "net/minecraft/client/renderer/ItemModelShaper";
    public static final String OLD_BLOCK_DISPATCHER = "net/minecraft/client/renderer/block/BlockRenderDispatcher";
    public static final String OLD_BLOCK_SHAPER = "net/minecraft/client/renderer/block/BlockModelShaper";
    public static final String MODEL_BLOCK_RENDERER = "net/minecraft/client/renderer/block/ModelBlockRenderer";
    static final String ROOT = RebuiltApiClasses.ROOT + "minecraft/client/renderer/";
    public static final String CALLS = ROOT + "LegacyBakedModelCalls";
    public static final String BLOCKS = ROOT + "LegacyBlockRenderDispatcher";
    public static final String ITEM_SHAPER = ROOT + "LegacyItemModelShaper";
    public static final String BLOCK_SHAPER = ROOT + "LegacyBlockModelShaper";
    public static final String OVERRIDES = ROOT + "LegacyItemOverrides";
    // The classes this bridge rebuilds inside each mod.
    private static final Set<String> REBUILT = Set.of(ITEM_SHAPER, BLOCKS, BLOCK_SHAPER, OVERRIDES);
    // Minecraft 1.21.5 replaced BakedModel with BlockStateModel; Fabric's intermediary name has meant the new one since.
    static final String FIRST_WITHOUT_BAKED_MODEL = "1.21.5";
    // The first Minecraft without BlockRenderDispatcher and the old ModelBlockRenderer members.
    static final String FIRST_WITHOUT_BLOCK_DISPATCHER = "26.1";

    private static final String MODEL = "L" + BLOCK_STATE_MODEL + ";";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String POSE = "Lcom/mojang/blaze3d/vertex/PoseStack;";

    /** {name, descriptor}: Minecraft 1.21.1's BakedModel members, Forge's and NeoForge's applyTransform and passes, in 26.x names. */
    static final String[][] OLD_MODEL_MEMBERS = {
        {"getQuads", "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"},
        {"useAmbientOcclusion", "()Z"},
        {"isGui3d", "()Z"},
        {"usesBlockLight", "()Z"},
        {"isCustomRenderer", "()Z"},
        {"getParticleIcon", "()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"},
        {"getTransforms", "()Lnet/minecraft/client/resources/model/cuboid/ItemTransforms;"},
        {"getOverrides", "()L" + OVERRIDES + ";"},
        {"applyTransform", "(Lnet/minecraft/world/item/ItemDisplayContext;" + POSE + "Z)" + MODEL},
        {"getRenderPasses", "(" + STACK + "Z)Ljava/util/List;"},
        {"getRenderTypes", "(" + STACK + "Z)Ljava/util/List;"},
    };

    /** The members above whose old interface had a default body, which an override may call through {@code super}. */
    static final Set<String> DEFAULT_BODIES = Set.of("applyTransform", "getRenderPasses", "getRenderTypes");

    /** {name, descriptor}: Minecraft 1.21.1's ModelBlockRenderer members old code calls on the renderer getModelRenderer() hands out. */
    static final String[][] OLD_RENDERER_MEMBERS = {
        {"renderModel", "(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                + "Lnet/minecraft/world/level/block/state/BlockState;" + MODEL + "FFFII)V"},
        {"enableCaching", "()V"},
        {"clearCache", "()V"},
    };

    private LegacyBakedItemBridge() {}

    /** Registers the rebuilt classes and redirects; {@link LegacyItemDrawBridge} calls it on hosts without ItemRenderer. */
    static void register(RetromodTransformer t, Function<String, ClassNode> host, Remapper names) {
        RebuiltApiClasses.registerTree(t, BLOCKS, names);
        RebuiltApiClasses.registerTree(t, CALLS, names);

        // Class redirects first, so calls on them reach the members below once renamed. The host has none of these
        // classes, so no mod built for it can name one. On Forge 26.2 and newer the rebuilt Forge model API replaces
        // the first two with its own, registered later in the chain.
        redirectRemovedClass(t, host, OLD_BAKED_MODEL, BLOCK_STATE_MODEL);
        redirectRemovedClass(t, host, OLD_ITEM_OVERRIDES, OVERRIDES);
        redirectRemovedClass(t, host, OLD_ITEM_SHAPER, ITEM_SHAPER);
        redirectRemovedClass(t, host, OLD_BLOCK_DISPATCHER, BLOCKS);
        redirectRemovedClass(t, host, OLD_BLOCK_SHAPER, BLOCK_SHAPER);

        // Mods keep the block renderer from reload listeners and constructors on, so each getter answers at any time.
        redirectGetter(t, host, LegacyItemDrawBridge.MINECRAFT, "getBlockRenderer");
        redirectGetter(t, host, LegacyItemDrawBridge.BLOCK_ENTITY_CONTEXT, "getBlockRenderDispatcher");
        redirectGetter(t, host, LegacyItemDrawBridge.ENTITY_CONTEXT, "getBlockRenderDispatcher");

        // Old model members on what a Fabric or NeoForge mod's BakedModel now names; keyed on the 26.x names, so a
        // model type another bridge rebuilds, such as Forge's, never matches them.
        ClassNode blockModel = host.apply(BLOCK_STATE_MODEL);
        for (String[] member : OLD_MODEL_MEMBERS) {
            if (declares(host, blockModel, member[0], member[1])) continue;
            String helperDesc = "(" + MODEL + member[1].substring(1);
            t.registerMethodRedirect(BLOCK_STATE_MODEL, member[0], member[1], CALLS, member[0], helperDesc, true);
            t.limitRedirectToModsBefore(BLOCK_STATE_MODEL, member[0], member[1], FIRST_WITHOUT_BAKED_MODEL);
            // An override's super call reaches the old default, which must not run that override again.
            if (DEFAULT_BODIES.contains(member[0])) t.registerSuperCallHelper(CALLS, member[0], helperDesc, superName(member[0]));
        }
        // The renderer keeps its name with new members, so only mods built before it lost these are rewritten.
        ClassNode renderer = host.apply(MODEL_BLOCK_RENDERER);
        for (String[] member : OLD_RENDERER_MEMBERS) {
            if (declares(host, renderer, member[0], member[1])) continue;
            t.registerMethodRedirect(MODEL_BLOCK_RENDERER, member[0], member[1], CALLS, member[0],
                    "(L" + MODEL_BLOCK_RENDERER + ";" + member[1].substring(1), true);
            t.limitRedirectToModsBefore(MODEL_BLOCK_RENDERER, member[0], member[1], FIRST_WITHOUT_BLOCK_DISPATCHER);
        }
    }

    /**
     * A mixin target this bridge rebuilt inside each mod, or old BakedModel, which it points at 26.x's block model; null for none.
     * Such a mixin would land on the mod's own copy, or inject old members into Minecraft's model, so it is dropped as before.
     */
    public static String removedMixinTarget(List<String> targets, UnaryOperator<String> redirectForCurrentMod) {
        for (String target : targets) {
            if (!target.startsWith("net/minecraft/")) continue;
            // Followed hop by hop, as a Fabric mixin still names its target by intermediary here.
            boolean oldModel = false;
            String current = target;
            for (int hop = 0; hop < 8; hop++) {
                oldModel |= current.equals(OLD_BAKED_MODEL);
                String next = redirectForCurrentMod.apply(current);
                if (next.equals(current)) break;
                current = next;
            }
            // Old BakedModel only where this bridge points it at the block model; Forge's rebuilt model keeps its own rule.
            if (REBUILT.contains(current) || (oldModel && current.equals(BLOCK_STATE_MODEL))) return target;
        }
        return null;
    }

    /** The helper an old default's {@code super} call goes to: {@code superGetQuads} for {@code getQuads}. */
    public static String superName(String member) {
        return "super" + Character.toUpperCase(member.charAt(0)) + member.substring(1);
    }

    private static void redirectGetter(RetromodTransformer t, Function<String, ClassNode> host, String owner, String name) {
        ClassNode node = host.apply(owner);
        if (node != null && node.methods.stream().anyMatch(m -> m.name.equals(name))) return;
        t.registerMethodRedirect(owner, name, "()L" + OLD_BLOCK_DISPATCHER + ";", BLOCKS, "get", "(Ljava/lang/Object;)L" + BLOCKS + ";", true);
    }

    private static void redirectRemovedClass(RetromodTransformer t, Function<String, ClassNode> host, String old, String rebuilt) {
        // A client host that still has the class keeps it; a server or an offline run answers by the target, which lost it.
        if (host.apply(HostBuffers.CLIENT_ANCHOR) != null && host.apply(old) != null) return;
        t.registerClassRedirect(old, rebuilt);
    }

    // Whether the host class or one of its interfaces already declares the member; an unread class declares nothing.
    private static boolean declares(Function<String, ClassNode> host, ClassNode node, String name, String desc) {
        if (node == null) return false;
        if (node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc))) return true;
        for (String parent : node.interfaces) {
            if (declares(host, host.apply(parent), name, desc)) return true;
        }
        return false;
    }
}
