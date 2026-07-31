/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * #162: {@code EntityType$Builder.build(String)} -> {@code build(ResourceKey)} bridge for
 * intermediary-namespace Fabric mods on pre-26.1 hosts. MC 1.21.2 flipped the descriptor of
 * {@code method_5905} from {@code (Ljava/lang/String;)Lclass_1299;} to
 * {@code (Lclass_5321;)Lclass_1299;}; a 1.20.1-1.21.1 mod registering an entity dies
 * {@code NoSuchMethodError} at {@code <clinit>} on a 1.21.2-1.21.11 host (ENGRAM 0.8.0-beta).
 *
 * <p>Host-probing (pitfall 9 in spirit): registers only when the host's
 * {@code class_1299$class_1300.method_5905} takes {@code class_5321}. On a &le;1.21.1 host the
 * String form still exists and the bridge must not fire; on a 26.1+ host the classes are
 * Mojang-named and the intermediary remap handles the mod instead. The rewritten call goes
 * receiver-as-arg0 to the embedded reflective
 * {@link com.retromod.polyfill.minecraft.RetroEntityTypeBuild}, which reconstructs the key the way
 * vanilla's own 1.21.2 migration did.
 */
public final class Pre1_21_2EntityTypeBuildBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String BUILDER = "net/minecraft/class_1299$class_1300";
    private static final String ENTITY_TYPE = "net/minecraft/class_1299";
    private static final String POLY = "com/retromod/polyfill/minecraft/RetroEntityTypeBuild";

    private Pre1_21_2EntityTypeBuildBridge() {}

    /** Probe the host builder's {@code method_5905} shape; bridge only the renamed-desc host. */
    public static void register(RetromodTransformer transformer) {
        try {
            Class<?> builder = Class.forName("net.minecraft.class_1299$class_1300", false,
                    Pre1_21_2EntityTypeBuildBridge.class.getClassLoader());
            Class<?> resourceKey = Class.forName("net.minecraft.class_5321", false,
                    Pre1_21_2EntityTypeBuildBridge.class.getClassLoader());
            boolean takesKey = false;
            boolean takesString = false;
            for (java.lang.reflect.Method m : builder.getMethods()) {
                if (!m.getName().equals("method_5905") || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] == resourceKey) takesKey = true;
                if (m.getParameterTypes()[0] == String.class) takesString = true;
            }
            if (!takesKey || takesString) {
                LOGGER.debug("The host still supports EntityType.Builder.build(String)");
                return;
            }
        } catch (Throwable t) {
            LOGGER.debug("EntityType.Builder support is not needed because intermediary host "
                    + "classes are unavailable ({})", t.getClass().getSimpleName());
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Added support for the old EntityType.Builder.build(String) method");
    }

    /** The unconditional registration (package-visible for the transform-shape test). */
    static void registerRedirects(RetromodTransformer transformer) {
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
        // to class_1299 at the call site.
        transformer.registerMethodRedirect(
                BUILDER, "method_5905", "(Ljava/lang/String;)L" + ENTITY_TYPE + ";",
                POLY, "build", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
    }
}
