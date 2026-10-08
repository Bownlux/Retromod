/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.retromod.mixin.runtime.ItemComponentsBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KubeJS 2001 reads and replaces an armor or tool item's default modifiers through accessors on
 * the {@code defaultModifiers} Multimap that 1.20.5 moved into the {@code ItemAttributeModifiers}
 * component. These cases run the bridge against an intermediary-named stand-in of that component
 * API, so they pin both directions of the conversion.
 */
class ItemComponentsBridgeTest {

    private static final String STUBS = "com/retromod/shim/fabric/itemstub/";
    private static final String OUTER = STUBS + "ItemStubs$";
    private static final Map<String, String> HOST_NAMES = Map.of(
            OUTER + "Registries", "net/minecraft/class_7923",
            OUTER + "SlotGroup", "net/minecraft/class_9274",
            OUTER + "Modifiers", "net/minecraft/class_9285",
            OUTER + "Components", "net/minecraft/class_9334",
            OUTER + "ComponentMap", "net/minecraft/class_9323");

    private final HostRenamingLoader loader =
            new HostRenamingLoader(STUBS, HOST_NAMES, ItemComponentsBridge.class);

    @Test
    @DisplayName("The old accessor sees the component's modifiers keyed by the bare attribute")
    void readsTheComponentAsAnOldMultimap() throws Exception {
        Object armor = new Object();
        Object item = item(List.of(entry(armor, "plate armor +6", feet())));

        Multimap<?, ?> modifiers = (Multimap<?, ?>) bridge("defaultModifiers", Object.class).invoke(null, item);

        assertEquals(List.of("plate armor +6"), List.copyOf(modifiers.get(cast(armor))),
                "keys are the attribute values, as the pre-1.20.5 field held them");
    }

    @Test
    @DisplayName("Handing a multimap back rebuilds the component and keeps the item's other components")
    void writesAMultimapBackIntoTheComponent() throws Exception {
        Object armor = new Object();
        Object toughness = new Object();
        Object item = item(List.of(entry(armor, "plate armor +6", feet())));
        Multimap<Object, Object> edited = ArrayListMultimap.create();
        edited.put(armor, "plate armor +8");
        edited.put(toughness, "toughness +2");

        bridge("setDefaultModifiers", Object.class, Object.class).invoke(null, item, edited);

        Multimap<?, ?> after = (Multimap<?, ?>) bridge("defaultModifiers", Object.class).invoke(null, item);
        assertEquals(List.of("plate armor +8"), List.copyOf(after.get(cast(armor))));
        assertEquals(List.of("toughness +2"), List.copyOf(after.get(cast(toughness))));
        Object components = item.getClass().getMethod("method_57347").invoke(item);
        assertEquals(1, components.getClass().getMethod("method_57829", Object.class)
                .invoke(components, "max_stack_size"), "unrelated components must survive the rebuild");
        Object entry = entries(item).get(0);
        Object slot = entry.getClass().getMethod("comp_2397").invoke(entry);
        assertSame(feet(), slot, "new modifiers keep the slot group the item's modifiers already used");
    }

    @Test
    @DisplayName("Setting the old maxDamage field makes the item damageable through its components")
    void maxDamageSetsBothDamageComponents() throws Exception {
        Object item = item(List.of());

        bridge("setMaxDamage", Object.class, Object.class).invoke(null, item, 250);
        bridge("setMaxStackSize", Object.class, Object.class).invoke(null, item, 1);

        assertEquals(250, component(item, "max_damage"));
        assertEquals(0, component(item, "damage"),
                "the game only damages items that carry both max_damage and damage");
        assertEquals(1, component(item, "max_stack_size"));
    }

    // ---- fixtures ------------------------------------------------------------------------

    private static Object component(Object item, Object type) throws Exception {
        Object components = item.getClass().getMethod("method_57347").invoke(item);
        return components.getClass().getMethod("method_57829", Object.class).invoke(components, type);
    }

    private Method bridge(String name, Class<?>... parameters) throws Exception {
        return loader.loadClass(ItemComponentsBridge.class.getName()).getMethod(name, parameters);
    }

    private Object feet() throws Exception {
        return loader.loadClass("net.minecraft.class_9274").getField("FEET").get(null);
    }

    private Object entry(Object attribute, Object modifier, Object slot) throws Exception {
        Class<?> holder = loader.loadClass((OUTER + "Holder").replace('/', '.'));
        Class<?> slotType = loader.loadClass("net.minecraft.class_9274");
        Object attributeHolder = holder.getConstructor(Object.class).newInstance(attribute);
        return loader.loadClass((OUTER + "Entry").replace('/', '.'))
                .getConstructor(holder, Object.class, slotType).newInstance(attributeHolder, modifier, slot);
    }

    private Object item(List<Object> defaults) throws Exception {
        return loader.loadClass((OUTER + "Item").replace('/', '.')).getConstructor(List.class).newInstance(defaults);
    }

    private List<?> entries(Object item) throws Exception {
        Object components = item.getClass().getMethod("method_57347").invoke(item);
        Object type = loader.loadClass("net.minecraft.class_9334").getField("field_49636").get(null);
        Object modifiers = components.getClass().getMethod("method_57829", Object.class).invoke(components, type);
        return (List<?>) modifiers.getClass().getMethod("comp_2393").invoke(modifiers);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
