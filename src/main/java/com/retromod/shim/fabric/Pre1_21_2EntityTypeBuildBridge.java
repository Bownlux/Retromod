/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.ClassResourceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bridges the old {@code EntityType.Builder.build(String)} for Fabric mods on pre-26.1 hosts. */
public final class Pre1_21_2EntityTypeBuildBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String BUILDER = "net/minecraft/class_1299$class_1300";
    private static final String ENTITY_TYPE = "net/minecraft/class_1299";
    private static final String POLY = "com/retromod/polyfill/minecraft/RetroEntityTypeBuild";

    private Pre1_21_2EntityTypeBuildBridge() {}

    /** Probe the host builder's {@code method_5905} shape; bridge only the renamed-desc host. */
    public static void register(RetromodTransformer transformer) {
        // 1.21.2 changed method_5905 from (String) to (class_5321), so a 1.20.1 to 1.21.1 mod that
        // registers an entity dies with NoSuchMethodError in <clinit> on a 1.21.2 to 1.21.11 host
        // (#162). A 26.1+ host has no intermediary classes; the full remap handles the mod there.
        var builder = ClassResourceInspector.read(BUILDER);
        if (builder == null || !ClassResourceInspector.exists("net/minecraft/class_5321")) {
            LOGGER.debug("EntityType.Builder support is not needed because intermediary host classes are unavailable");
            return;
        }
        // Bridge only when the host takes the key and the String form is gone. A 1.21.1 or older
        // host still has the String form, so the bridge must not fire there (pitfall #9).
        boolean takesKey = builder.methods.stream()
                .anyMatch(m -> m.name.equals("method_5905")
                        && m.desc.startsWith("(Lnet/minecraft/class_5321;)"));
        boolean takesString = builder.methods.stream()
                .anyMatch(m -> m.name.equals("method_5905")
                        && m.desc.startsWith("(Ljava/lang/String;)"));
        if (!takesKey || takesString) {
            LOGGER.debug("The host still supports EntityType.Builder.build(String)");
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Added support for the old EntityType.Builder.build(String) method");
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        // Ship the polyfill as an embeddable synthetic so the redirect target resolves from the
        // mod's context too (Fabric injects; the class is also jar-resident).
        try (java.io.InputStream in = Pre1_21_2EntityTypeBuildBridge.class.getClassLoader()
                .getResourceAsStream(POLY + ".class")) {
            if (in != null && !transformer.getSyntheticClasses().containsKey(POLY)) {
                transformer.registerSyntheticClass(POLY, in.readAllBytes());
            }
        } catch (Throwable ignored) {
            // jar-resident copy still serves on Fabric
        }
        // Receiver-as-arg0: auto-devirtualized to INVOKESTATIC; the Object return CHECKCASTs back
        // to class_1299 at the call site. The polyfill builds under the id the type is registered
        // under when EntityTypeBuildKeyAdapter finds it.
        transformer.registerMethodRedirect(
                BUILDER, "method_5905", "(Ljava/lang/String;)L" + ENTITY_TYPE + ";",
                POLY, "build", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
    }
}
