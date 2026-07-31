/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.text.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Factory shim for TranslatableContents/TranslatableText constructor changes.
 *
 * In old MC (pre-1.19.1), TranslatableText WAS a Component, so you could do:
 *   Component text = new TranslatableText("key");
 *
 * In 26.1, TranslatableContents is not a Component; it's the inner contents.
 * You need: Component text = MutableComponent.create(new TranslatableContents(key, null, args));
 *
 * This shim returns a MutableComponent (which IS a Component) wrapping the
 * TranslatableContents, so old mods that assign the result to a Component field work.
 */
public class TranslatableContentsShim {

    private static volatile Constructor<?> threeArgConstructor;
    private static volatile Method mutableComponentCreate;

    /**
     * Factory for old 1-arg constructor: new TranslatableText(key)
     * Returns a MutableComponent wrapping TranslatableContents(key, null, new Object[0])
     */
    public static Object create(String key) {
        return createWithArgs(key, new Object[0]);
    }

    /**
     * Factory for old 2-arg constructor: new TranslatableText(key, args)
     * Returns a MutableComponent wrapping TranslatableContents(key, null, args)
     */
    public static Object createWithArgs(String key, Object[] args) {
        try {
            if (threeArgConstructor == null) {
                synchronized (TranslatableContentsShim.class) {
                    if (threeArgConstructor == null) {
                        Class<?> contentsClass = Class.forName(
                            "net.minecraft.network.chat.contents.TranslatableContents");
                        threeArgConstructor = contentsClass.getConstructor(
                            String.class, String.class, Object[].class);

                        // MutableComponent.create(ComponentContents) -> MutableComponent
                        Class<?> mcClass = Class.forName(
                            "net.minecraft.network.chat.MutableComponent");
                        Class<?> contentsInterface = Class.forName(
                            "net.minecraft.network.chat.ComponentContents");
                        mutableComponentCreate = mcClass.getMethod("create", contentsInterface);
                    }
                }
            }
            // Create TranslatableContents then wrap in MutableComponent
            Object contents = threeArgConstructor.newInstance(key, null, args);
            return mutableComponentCreate.invoke(null, contents);
        } catch (Exception e) {
            // Fallback: try Component.translatable(key)
            try {
                Class<?> componentClass = Class.forName("net.minecraft.network.chat.Component");
                return componentClass.getMethod("translatable", String.class, Object[].class)
                    .invoke(null, key, args);
            } catch (Exception e2) {
                throw new RuntimeException(
                    "Retromod: Failed to create translatable text for key '" + key + "'", e);
            }
        }
    }
}
