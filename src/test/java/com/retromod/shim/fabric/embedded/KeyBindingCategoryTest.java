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
}
