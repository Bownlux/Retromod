/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Gives old mods' Minecraft.getEntityModels() calls a set that works before 26.x has built its model manager. */
public final class LegacyEntityModelsBridge {

    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String GETTER = "getEntityModels";
    static final String SET = "net/minecraft/client/model/geom/EntityModelSet";
    static final String GETTER_DESC = "()L" + SET + ";";
    static final String MODEL_MANAGER = "net/minecraft/client/resources/model/ModelManager";
    public static final String MODELS = RebuiltApiClasses.ROOT + "minecraft/client/model/geom/LegacyEntityModels";
    static final String HELPER = "entityModels";
    static final String HELPER_DESC = "(L" + MINECRAFT + ";)L" + SET + ";";
    /** Minecraft 1.21.4 moved the entity models into the model manager, so a mod built for it already expects that. */
    static final String FIRST_VERSION_WITH_MANAGED_MODELS = "1.21.4";

    private LegacyEntityModelsBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostManagesEntityModels(host)) return;
        RebuiltApiClasses.registerTree(t, MODELS);
        // Old mods called the getter while their items registered, where 26.x has no model manager yet and throws.
        t.registerWrappingMethodRedirect(MINECRAFT, GETTER, GETTER_DESC, MODELS, HELPER, HELPER_DESC);
        t.limitRedirectToModsBefore(MINECRAFT, GETTER, GETTER_DESC, FIRST_VERSION_WITH_MANAGED_MODELS);
    }

    /** Whether the host's model manager hands out its entity models, and its set can be extended by the forwarding set. */
    static boolean hostManagesEntityModels(Function<String, ClassNode> host) {
        ClassNode set = host.apply(SET);
        ClassNode manager = host.apply(MODEL_MANAGER);
        // A dedicated server or an offline run without a client jar reads neither, and the 26.1 shim that calls this stands for the host.
        if (set == null && manager == null) return true;
        if (set == null || manager == null || (set.access & Opcodes.ACC_FINAL) != 0) return false;
        boolean extendable = hasPublic(set, "<init>", "(Ljava/util/Map;)V")
                && hasPublic(set, "bakeLayer", "(Lnet/minecraft/client/model/geom/ModelLayerLocation;)Lnet/minecraft/client/model/geom/ModelPart;");
        return extendable && hasPublic(manager, "entityModels", "()Ljava/util/function/Supplier;");
    }

    private static boolean hasPublic(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc)
                && (m.access & Opcodes.ACC_PUBLIC) != 0 && (m.access & Opcodes.ACC_FINAL) == 0);
    }
}
