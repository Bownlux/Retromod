/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.ClassResourceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * Redirects pre-1.20.5 {@code Identifier} (ResourceLocation) constructor calls to the
 * static factories that replaced them, for intermediary-namespace Fabric mods on pre-26.1 hosts.
 *
 * <p>1.20.5 deleted the public {@code class_2960} ctors in favor of {@code parse} and
 * {@code fromNamespaceAndPath}; a mod compiled against &le;1.20.4 still emits
 * {@code INVOKESPECIAL class_2960.<init>(...)}, a {@code NoSuchMethodError} on a 1.20.5+ host.
 * {@code RegistryPolyfill} handles the Mojang-named classes, but the intermediary&rarr;Mojang
 * remap is gated off on pre-26.1 Fabric hosts (#21), so the bytecode still says {@code class_2960}.
 *
 * <p>The factory intermediary IDs drift between MC versions, so the bridge inspects the host's
 * {@code class_2960} resource for its static {@code (String)} and {@code (String,String)}
 * factories rather than carrying a per-version table.
 */
public final class Pre1_20_5IdentifierCtorBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String IDENTIFIER = "net/minecraft/class_2960";
    private static final String L_IDENTIFIER = "L" + IDENTIFIER + ";";

    private Pre1_20_5IdentifierCtorBridge() {}

    /** Wire constructor to factory redirects, discovering the host's factory names from bytecode. */
    public static void register(RetromodTransformer transformer) {
        ClassNode identifier = ClassResourceInspector.read(IDENTIFIER);
        if (identifier == null) {
            LOGGER.debug("Identifier constructor support is not needed because class_2960 is unavailable");
            return;
        }

        String parseName = findStaticFactory(identifier, "(Ljava/lang/String;)" + L_IDENTIFIER);
        String fromNsPathName = findStaticFactory(identifier,
                "(Ljava/lang/String;Ljava/lang/String;)" + L_IDENTIFIER);

        int registered = 0;
        if (parseName != null) {
            transformer.registerConstructorRedirect(
                    IDENTIFIER, "(Ljava/lang/String;)V",
                    IDENTIFIER, parseName,
                    "(Ljava/lang/String;)" + L_IDENTIFIER);
            registered++;
        }
        if (fromNsPathName != null) {
            transformer.registerConstructorRedirect(
                    IDENTIFIER, "(Ljava/lang/String;Ljava/lang/String;)V",
                    IDENTIFIER, fromNsPathName,
                    "(Ljava/lang/String;Ljava/lang/String;)" + L_IDENTIFIER);
            registered++;
        }

        if (registered == 0) {
            LOGGER.debug("The host still appears to support the old Identifier constructors");
        } else {
            LOGGER.debug("Added {} Identifier constructor {}", registered,
                    registered == 1 ? "redirect" : "redirects");
        }
    }

    /**
     * Name of the one public-static {@code (paramTypes...) -> cls} method on {@code cls}, or null
     * if there are zero or several. Don't guess: the wrong factory corrupts every Identifier built.
     */
    private static String findStaticFactory(ClassNode cls, String descriptor) {
        String match = null;
        for (var m : cls.methods) {
            int required = Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC;
            if ((m.access & required) != required || !m.desc.equals(descriptor)) continue;
            if (match != null) {
                LOGGER.warn("Found two possible Identifier factories, {} and {}. Retromod will "
                                + "leave this constructor unchanged because it cannot choose safely.",
                        match, m.name);
                return null;
            }
            match = m.name;
        }
        return match;
    }
}
