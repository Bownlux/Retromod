/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/** Builds Minecraft translatable and literal text through reflection. */
public final class McI18n {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    // Retromod has no compile-time Minecraft classpath, so Text or Component is found at runtime.
    private static volatile Class<?> textClass;
    private static volatile Method translatableMethod;
    private static volatile Method translatableWithArgsMethod;
    private static volatile Method literalMethod;
    private static volatile boolean resolved;

    private McI18n() {}

    /**
     * Build a Text/Component resolved against the current MC language at render time.
     * Falls back to a literal Text of the key, or null if neither path is reachable.
     *
     * @param translationKey the translation key
     */
    public static Object translatable(String translationKey) {
        resolve();
        try {
            if (translatableMethod != null) {
                return translatableMethod.invoke(null, translationKey);
            }
            // Without the translatable API, show the raw key, as Minecraft does for a missing
            // translation, instead of crashing.
            if (literalMethod != null) {
                return literalMethod.invoke(null, translationKey);
            }
        } catch (Exception e) {
            LOGGER.debug("McI18n.translatable({}) failed: {}", translationKey, e.getMessage());
        }
        return null;
    }

    /** Like {@link #translatable(String)}, with values for the {@code %s} slots in the translation. */
    public static Object translatable(String translationKey, Object... args) {
        if (args == null || args.length == 0) return translatable(translationKey);
        resolve();
        // Pass the arguments as strings, which every supported version accepts.
        Object[] text = new Object[args.length];
        for (int i = 0; i < args.length; i++) text[i] = String.valueOf(args[i]);
        try {
            if (translatableWithArgsMethod != null) {
                return translatableWithArgsMethod.invoke(null, translationKey, (Object) text);
            }
        } catch (Exception e) {
            LOGGER.debug("McI18n.translatable({}, args) failed: {}", translationKey,
                    e.getMessage());
        }
        // Without the two-argument form, fall back to the key without arguments.
        return translatable(translationKey);
    }

    /** Build a literal Text/Component for text that should never be translated. */
    public static Object literal(String text) {
        resolve();
        try {
            if (literalMethod != null) {
                return literalMethod.invoke(null, text);
            }
        } catch (Exception e) {
            LOGGER.debug("McI18n.literal({}) failed: {}", text, e.getMessage());
        }
        return null;
    }

    private static void resolve() {
        if (resolved) return;
        synchronized (McI18n.class) {
            if (resolved) return;
            textClass = McReflect.findClass(
                "net.minecraft.text.Text",
                "net.minecraft.network.chat.Component"
            );
            if (textClass != null) {
                translatableMethod = McReflect.findMethod(textClass,
                        new Class[]{String.class}, "translatable");
                translatableWithArgsMethod = McReflect.findMethod(textClass,
                        new Class[]{String.class, Object[].class}, "translatable");
                literalMethod = McReflect.findMethod(textClass,
                        new Class[]{String.class}, "literal");
                // older MC has no Text.literal, try Text.of
                if (literalMethod == null) {
                    literalMethod = McReflect.findMethod(textClass,
                            new Class[]{String.class}, "of");
                }
            }
            resolved = true;
        }
    }
}
