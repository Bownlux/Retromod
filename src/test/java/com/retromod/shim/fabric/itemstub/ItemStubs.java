/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.itemstub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 1.21.1 item component API in miniature, under intermediary member names. The test loader
 * renames each nested class to the intermediary class it plays.
 */
public final class ItemStubs {

    private ItemStubs() {}

    /** {@code Holder}: {@code value()} is {@code comp_349}. */
    public record Holder(Object comp_349) {}

    /** {@code BuiltInRegistries.ATTRIBUTE}, whose {@code wrapAsHolder} is {@code method_47983}. */
    public static final class Registry {
        public Holder method_47983(Object value) {
            return new Holder(value);
        }
    }

    /** {@code BuiltInRegistries}, renamed to {@code class_7923}. */
    public static final class Registries {
        public static final Registry field_41190 = new Registry();
    }

    /** {@code EquipmentSlotGroup}, renamed to {@code class_9274}. */
    public static final class SlotGroup {
        public static final SlotGroup field_49217 = new SlotGroup("mainhand");
        public static final SlotGroup FEET = new SlotGroup("feet");
        public final String name;

        public SlotGroup(String name) {
            this.name = name;
        }
    }

    /** {@code ItemAttributeModifiers.Entry}: attribute, modifier and slot. */
    public record Entry(Holder comp_2395, Object comp_2396, SlotGroup comp_2397) {}

    /** {@code ItemAttributeModifiers}, renamed to {@code class_9285}. */
    public record Modifiers(List<Entry> comp_2393) {
        public static ModifiersBuilder method_57480() {
            return new ModifiersBuilder();
        }
    }

    /** {@code ItemAttributeModifiers.Builder}. */
    public static final class ModifiersBuilder {
        private final List<Entry> entries = new ArrayList<>();

        public ModifiersBuilder method_57487(Holder attribute, Object modifier, SlotGroup slot) {
            entries.add(new Entry(attribute, modifier, slot));
            return this;
        }

        public Modifiers method_57486() {
            return new Modifiers(List.copyOf(entries));
        }
    }

    /** {@code DataComponents}, renamed to {@code class_9334}. */
    public static final class Components {
        public static final Object field_49636 = "attribute_modifiers";
        public static final Object field_50071 = "max_stack_size";
        public static final Object field_50072 = "max_damage";
        public static final Object field_49629 = "damage";
    }

    /** {@code DataComponentMap}, renamed to {@code class_9323}. */
    public static final class ComponentMap {
        public final Map<Object, Object> values;

        public ComponentMap(Map<Object, Object> values) {
            this.values = values;
        }

        public Object method_57829(Object type) {
            return values.get(type);
        }

        public static ComponentMapBuilder method_57827() {
            return new ComponentMapBuilder();
        }
    }

    /** {@code DataComponentMap.Builder}. */
    public static final class ComponentMapBuilder {
        private final Map<Object, Object> values = new LinkedHashMap<>();

        public ComponentMapBuilder method_57839(ComponentMap other) {
            values.putAll(other.values);
            return this;
        }

        public ComponentMapBuilder method_57840(Object type, Object value) {
            values.put(type, value);
            return this;
        }

        public ComponentMap method_57838() {
            return new ComponentMap(new LinkedHashMap<>(values));
        }
    }

    /** {@code Item}: the default components field and getter, under intermediary names. */
    public static class Item {
        private final ComponentMap field_49263;

        public Item(List<Entry> defaults) {
            Map<Object, Object> values = new LinkedHashMap<>();
            values.put(Components.field_49636, new Modifiers(defaults));
            values.put("max_stack_size", 1);
            this.field_49263 = new ComponentMap(values);
        }

        public ComponentMap method_57347() {
            return field_49263;
        }
    }
}
