/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.keystub;

import java.util.ArrayList;
import java.util.List;

/** The 1.21.11 keybind classes in miniature, under intermediary names once the test loader renames them. */
public final class KeyStubs {

    private KeyStubs() {}

    /** {@code Identifier}, renamed to {@code class_2960}, with the decoy {@code tryBuild} that returns null. */
    public record Id(String namespace, String path) {
        public static Id method_60655(String namespace, String path) {
            return new Id(namespace, path);
        }

        public static Id method_43902(String namespace, String path) {
            return null;
        }
    }

    /** {@code KeyBinding}, renamed to {@code class_304}: only the Category constructors are left. */
    public static class KeyBinding {
        public final String translationKey;
        public final Object inputType;
        public final int keyCode;
        public final Category category;

        public KeyBinding(String translationKey, Object inputType, int keyCode, Category category) {
            this.translationKey = translationKey;
            this.inputType = inputType;
            this.keyCode = keyCode;
            this.category = category;
        }

        public KeyBinding(String translationKey, int keyCode, Category category) {
            this(translationKey, "KEYSYM", keyCode, category);
        }

        /** {@code KeyBinding.Category}, renamed to {@code class_304$class_11900}, with its register. */
        public record Category(Id id) {
            public static final List<Category> SORT_ORDER = new ArrayList<>();

            public static Category method_74698(Id id) {
                Category category = new Category(id);
                if (SORT_ORDER.contains(category)) {
                    throw new IllegalArgumentException("Category '" + id + "' is already registered.");
                }
                SORT_ORDER.add(category);
                return category;
            }
        }
    }
}
