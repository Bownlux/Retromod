/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric.embedded;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #298: on 26.x a keybind category only sorts correctly once it is registered. The shim built its
 * categories with the constructor, so two mods' categories compared equal, and a keybind list kept
 * in a sorted set (Amecs) dropped one mod's keys.
 */
class KeyBindingCategoryTest {

    /** Stands in for {@code KeyMapping.Category}: a one-field record with a static register. */
    public record Category(String id) {
        static final List<Category> SORT_ORDER = new ArrayList<>();

        public static Category register(String id) {
            Category category = new Category(id);
            if (SORT_ORDER.contains(category)) {
                throw new IllegalArgumentException("Category '" + id + "' is already registered.");
            }
            SORT_ORDER.add(category);
            return category;
        }
    }

    @BeforeEach
    void clear() {
        Category.SORT_ORDER.clear();
    }

    @Test
    @DisplayName("a new category is registered so it gets a sort slot")
    void registersTheCategory() throws Exception {
        Object category = KeyBindingShim.registerCategory(Category.class,
                Category.class.getConstructor(String.class), "autoclicky:main");

        assertEquals(new Category("autoclicky:main"), category);
        assertEquals(List.of(new Category("autoclicky:main")), Category.SORT_ORDER);
    }

    @Test
    @DisplayName("a category that is already registered is reused instead of failing")
    void reusesARegisteredCategory() throws Exception {
        Category.register("bridgingmod:category");

        Object category = KeyBindingShim.registerCategory(Category.class,
                Category.class.getConstructor(String.class), "bridgingmod:category");

        assertEquals(new Category("bridgingmod:category"), category,
                "an equal record finds the existing slot");
        assertEquals(1, Category.SORT_ORDER.size());
    }

    /** Stands in for an intermediary {@code class_2960}: the real factory and a tryBuild-shaped decoy. */
    public record IntermediaryId(String namespace, String path) {
        public static IntermediaryId method_43902(String namespace, String path) {
            return null;
        }

        public static IntermediaryId method_60655(String namespace, String path) {
            return new IntermediaryId(namespace, path);
        }
    }

    /** Stands in for a Mojang-named {@code Identifier}. */
    public record MojangId(String namespace, String path) {
        public static MojangId fromNamespaceAndPath(String namespace, String path) {
            return new MojangId(namespace, path);
        }
    }

    /** Stands in for {@code KeyMapping.Category} whose constructor takes the host's id type. */
    public record IdCategory(IntermediaryId id) {
        static final List<IdCategory> SORT_ORDER = new ArrayList<>();

        public static IdCategory register(IntermediaryId id) {
            IdCategory category = new IdCategory(id);
            SORT_ORDER.add(category);
            return category;
        }
    }

    @Test
    @DisplayName("an intermediary id is built with method_60655, never the tryBuild decoy that returns null")
    void buildsAnIntermediaryIdByName() throws Exception {
        Object id = KeyBindingShim.identifier(IntermediaryId.class, "retromod_compat", "key_categories_jade");

        assertEquals(new IntermediaryId("retromod_compat", "key_categories_jade"), id);
    }

    @Test
    @DisplayName("a Mojang-named id class with only fromNamespaceAndPath also works")
    void buildsAMojangId() throws Exception {
        Object id = KeyBindingShim.identifier(MojangId.class, "minecraft", "misc");

        assertEquals(new MojangId("minecraft", "misc"), id);
    }

    @Test
    @DisplayName("a category whose constructor takes the id class is registered with that id")
    void registersACategoryKeyedByTheIdClass() throws Exception {
        IdCategory.SORT_ORDER.clear();
        java.lang.reflect.Constructor<?> constructor = IdCategory.class.getConstructor(IntermediaryId.class);
        Object id = KeyBindingShim.identifier(constructor.getParameterTypes()[0], "retromod_compat", "jade");

        Object category = KeyBindingShim.registerCategory(IdCategory.class, constructor, id);

        IdCategory expected = new IdCategory(new IntermediaryId("retromod_compat", "jade"));
        assertEquals(expected, category);
        assertEquals(List.of(expected), IdCategory.SORT_ORDER);
    }
}
