/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.tagstub;

import java.util.HashMap;
import java.util.Map;

/**
 * The 1.21.1 item stack custom data API in miniature, under intermediary member names. The test
 * loader renames each nested class to the intermediary class it plays.
 */
public final class TagStubs {

    private TagStubs() {}

    /** {@code CompoundTag}, renamed to {@code class_2487}. */
    public static final class CompoundTag {
        public final Map<String, Object> values = new HashMap<>();

        public CompoundTag copy() {
            CompoundTag copy = new CompoundTag();
            copy.values.putAll(values);
            return copy;
        }
    }

    /** {@code Enchantment}, renamed to {@code class_1887}. */
    public static final class Enchantment {}

    /** {@code DataComponentType}, renamed to {@code class_9331}. */
    public static final class ComponentType {}

    /** {@code DataComponents}, renamed to {@code class_9334}; {@code CUSTOM_DATA} is {@code field_49628}. */
    public static final class DataComponents {
        public static final ComponentType field_49628 = new ComponentType();
    }

    /** {@code CustomData}, renamed to {@code class_9279}. Like the host, {@code of} copies the tag. */
    public static final class CustomData {
        private final CompoundTag tag;

        private CustomData(CompoundTag tag) {
            this.tag = tag;
        }

        public static CustomData method_57456(CompoundTag tag) {
            return new CustomData(tag.copy());
        }

        public boolean method_57458() {
            return tag.values.isEmpty();
        }

        public CompoundTag method_57463() {
            return tag;
        }
    }

    /** {@code ItemStack}, renamed to {@code class_1799}. A copy shares component values, as on the host. */
    public static final class ItemStack {
        private final Map<ComponentType, Object> components = new HashMap<>();

        public Object method_57824(ComponentType type) {
            return components.get(type);
        }

        public Object method_57379(ComponentType type, Object value) {
            return components.put(type, value);
        }

        public Object method_57381(ComponentType type) {
            return components.remove(type);
        }

        public ItemStack copy() {
            ItemStack copy = new ItemStack();
            copy.components.putAll(components);
            return copy;
        }
    }
}
