/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.fabric.embedded.LegacyResourceConditionSupport;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges custom resource conditions that older Fabric mods register with
 * {@code ResourceConditions.register(Identifier, Predicate<JsonObject>)}, for intermediary-namespace
 * Fabric mods on pre-26.1 hosts.
 *
 * <p>With Minecraft 1.20.5 Fabric API replaced that method with
 * {@code register(ResourceConditionType)}, where a type carries a codec for its condition. A mod
 * that still called the old form died with {@code NoSuchMethodError} in its initializer, so the
 * rest of its setup never ran and every data file guarded by its condition was skipped as
 * unknown. Palladium's feature flag condition is one.
 *
 * <p>The call goes to {@link LegacyResourceConditionSupport}, which registers a type whose
 * condition keeps the declared JSON object and tests it with the mod's predicate. The old
 * {@code objectMatchesConditions(JsonObject)} helper is not bridged.
 */
public final class Pre1_20_5ResourceConditionBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String CONDITIONS = "net/fabricmc/fabric/api/resource/conditions/v1/ResourceConditions";
    static final String CONDITION_TYPE = "net/fabricmc/fabric/api/resource/conditions/v1/ResourceConditionType";
    static final String SUPPORT = "com/retromod/shim/fabric/embedded/LegacyResourceConditionSupport";
    static final String OLD_REGISTER = "(Lnet/minecraft/class_2960;Ljava/util/function/Predicate;)V";
    static final String NEW_REGISTER = "(L" + CONDITION_TYPE + ";)V";
    static final String SUPPORT_REGISTER = "(Ljava/lang/Object;Ljava/util/function/Predicate;)V";

    private Pre1_20_5ResourceConditionBridge() {}

    /** Registers the redirect only on a Fabric API that has the typed form and not the old one. */
    public static void register(RetromodTransformer transformer) {
        ClassNode conditions = ClassResourceInspector.read(CONDITIONS);
        if (conditions == null || hasMethod(conditions, "register", OLD_REGISTER)
                || !hasMethod(conditions, "register", NEW_REGISTER)) {
            LOGGER.debug("Fabric API still has predicate-based resource conditions");
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Added support for predicate-based resource conditions");
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, SUPPORT, LegacyResourceConditionSupport.class);
        transformer.registerMethodRedirect(CONDITIONS, "register", OLD_REGISTER,
                SUPPORT, "register", SUPPORT_REGISTER);
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        return false;
    }
}
