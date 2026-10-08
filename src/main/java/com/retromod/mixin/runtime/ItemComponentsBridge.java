/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stands in for the {@code Item} fields that 1.20.5 moved into the item's default data
 * components: {@code defaultModifiers} on armor, digger, sword and trident items, and
 * {@code maxStackSize}, {@code maxDamage}, {@code isFireResistant}, {@code rarity} and
 * {@code foodProperties} on every item.
 *
 * <p>Each field setter replaces the matching component in the item's default components, so items
 * created afterwards see the new value. Setting {@code maxDamage} also adds a zero damage component
 * when the item had none, because the game treats an item as damageable only when both exist.
 *
 * <p>Before 1.20.5 these items kept their default attribute modifiers in a Guava
 * {@code Multimap<Attribute, AttributeModifier>}, and mods read or replaced it through an
 * {@code @Accessor}. The modifiers now live in the item's {@code ItemAttributeModifiers} component,
 * keyed by attribute holder and slot group. {@link com.retromod.mixin.MixinAccessorFieldMigration}
 * points those accessors here.
 *
 * <p>{@link #defaultModifiers} returns a new multimap of the component's entries, keyed by the bare
 * attribute as before. Changing that multimap changes nothing until the mod hands it back, which is
 * what mods that edit modifiers already do. {@link #setDefaultModifiers} rebuilds the component
 * from the multimap and stores it in the item's default components. Every new modifier takes the
 * slot group the item's existing modifiers use, or the main hand when it has none, because the old
 * map had no slot of its own.
 *
 * <p>No compile-time Minecraft or Guava dependency, for the same per-mod embedding reason as
 * {@link AreaEffectCloudEffectsBridge}. Each member is looked up under its Mojang name and then its
 * intermediary name, so the bridge works on Mojang-named and intermediary-named hosts alike.
 */
public final class ItemComponentsBridge {

    private static final String MULTIMAP_FACTORY = "com.google.common.collect.ArrayListMultimap";
    private static final String MULTIMAP = "com.google.common.collect.Multimap";
    private static final String[] DATA_COMPONENTS =
            {"net.minecraft.core.component.DataComponents", "net.minecraft.class_9334"};
    private static final String[] DATA_COMPONENT_MAP =
            {"net.minecraft.core.component.DataComponentMap", "net.minecraft.class_9323"};
    private static final String[] ITEM_ATTRIBUTE_MODIFIERS =
            {"net.minecraft.world.item.component.ItemAttributeModifiers", "net.minecraft.class_9285"};
    private static final String[] EQUIPMENT_SLOT_GROUP =
            {"net.minecraft.world.entity.EquipmentSlotGroup", "net.minecraft.class_9274"};
    private static final String[] BUILT_IN_REGISTRIES =
            {"net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.class_7923"};

    private static final String[] ATTRIBUTE_MODIFIERS = {"ATTRIBUTE_MODIFIERS", "field_49636"};
    private static final String[] ATTRIBUTE_REGISTRY = {"ATTRIBUTE", "field_41190"};
    private static final String[] MAINHAND = {"MAINHAND", "field_49217"};
    private static final String[] COMPONENTS_FIELD = {"components", "field_49263"};
    private static final String[] COMPONENTS = {"components", "method_57347"};
    private static final String[] GET = {"get", "method_57829"};
    private static final String[] MODIFIERS = {"modifiers", "comp_2393"};
    private static final String[] ENTRY_ATTRIBUTE = {"attribute", "comp_2395"};
    private static final String[] ENTRY_MODIFIER = {"modifier", "comp_2396"};
    private static final String[] ENTRY_SLOT = {"slot", "comp_2397"};
    private static final String[] HOLDER_VALUE = {"value", "comp_349"};
    private static final String[] WRAP_AS_HOLDER = {"wrapAsHolder", "method_47983"};
    private static final String[] MODIFIERS_BUILDER = {"builder", "method_57480"};
    private static final String[] BUILDER_ADD = {"add", "method_57487"};
    private static final String[] BUILDER_BUILD = {"build", "method_57486"};
    private static final String[] MAP_BUILDER = {"builder", "method_57827"};
    private static final String[] MAP_ADD_ALL = {"addAll", "method_57839"};
    private static final String[] MAP_SET = {"set", "method_57840"};
    private static final String[] MAP_BUILD = {"build", "method_57838"};
    private static final String[] MAX_STACK_SIZE = {"MAX_STACK_SIZE", "field_50071"};
    private static final String[] MAX_DAMAGE = {"MAX_DAMAGE", "field_50072"};
    private static final String[] DAMAGE = {"DAMAGE", "field_49629"};
    private static final String[] RARITY = {"RARITY", "field_50073"};
    private static final String[] FOOD = {"FOOD", "field_50075"};
    private static final String[] FIRE_RESISTANT = {"FIRE_RESISTANT", "field_50076"};
    private static final String[] UNIT = {"net.minecraft.util.Unit", "net.minecraft.class_3902"};
    private static final String[] UNIT_INSTANCE = {"INSTANCE", "field_17274"};

    private ItemComponentsBridge() {}

    /** The item's default modifiers as the old {@code Multimap<Attribute, AttributeModifier>}. */
    public static Object defaultModifiers(Object item) {
        try {
            ClassLoader loader = item.getClass().getClassLoader();
            Object multimap = Class.forName(MULTIMAP_FACTORY, false, loader).getMethod("create").invoke(null);
            // Through the public interface: Guava's implementation classes are package-private.
            Method put = Class.forName(MULTIMAP, false, loader).getMethod("put", Object.class, Object.class);
            for (Object entry : entries(item)) {
                Object attribute = call(call(entry, ENTRY_ATTRIBUTE), HOLDER_VALUE);
                put.invoke(multimap, attribute, call(entry, ENTRY_MODIFIER));
            }
            return multimap;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new UnsupportedOperationException(
                    "Retromod could not read the default attribute modifiers of " + item, e);
        }
    }

    /** Replaces the item's default modifiers with the entries of an old-style multimap. */
    public static void setDefaultModifiers(Object item, Object multimap) {
        try {
            ClassLoader loader = item.getClass().getClassLoader();
            Object slot = slotGroupFor(item, loader);
            Object registry = staticField(loader, BUILT_IN_REGISTRIES, ATTRIBUTE_REGISTRY);
            Object builder = callStatic(loader, ITEM_ATTRIBUTE_MODIFIERS, MODIFIERS_BUILDER);
            Object entries = Class.forName(MULTIMAP, false, loader).getMethod("entries").invoke(multimap);
            for (Object pair : (Iterable<?>) entries) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) pair;
                Object holder = call(registry, WRAP_AS_HOLDER, entry.getKey());
                builder = call(builder, BUILDER_ADD, holder, entry.getValue(), slot);
            }
            setComponent(item, ATTRIBUTE_MODIFIERS, call(builder, BUILDER_BUILD));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new UnsupportedOperationException(
                    "Retromod could not replace the default attribute modifiers of " + item, e);
        }
    }

    /** {@code Item.maxStackSize = value}, now the {@code max_stack_size} component. */
    public static void setMaxStackSize(Object item, Object value) {
        setComponent(item, MAX_STACK_SIZE, value);
    }

    /** {@code Item.maxDamage = value}, now the {@code max_damage} and {@code damage} components. */
    public static void setMaxDamage(Object item, Object value) {
        setComponent(item, MAX_DAMAGE, value);
        try {
            if (component(item, DAMAGE) == null) setComponent(item, DAMAGE, 0);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedOperationException("Retromod could not make " + item + " damageable", e);
        }
    }

    /** {@code Item.isFireResistant = value}, now the presence of the {@code fire_resistant} component. */
    public static void setFireResistant(Object item, Object value) {
        try {
            Object unit = Boolean.TRUE.equals(value)
                    ? staticField(item.getClass().getClassLoader(), UNIT, UNIT_INSTANCE) : null;
            setComponent(item, FIRE_RESISTANT, unit);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedOperationException("Retromod could not change fire resistance of " + item, e);
        }
    }

    /** {@code Item.rarity = value}, now the {@code rarity} component. */
    public static void setRarity(Object item, Object value) {
        setComponent(item, RARITY, value);
    }

    /** {@code Item.foodProperties = value}, now the {@code food} component. */
    public static void setFoodProperties(Object item, Object value) {
        setComponent(item, FOOD, value);
    }

    /** Replaces one default component; a null value removes it, as the map builder does. */
    private static void setComponent(Object item, String[] componentType, Object value) {
        try {
            ClassLoader loader = item.getClass().getClassLoader();
            Object type = staticField(loader, DATA_COMPONENTS, componentType);
            Object mapBuilder = callStatic(loader, DATA_COMPONENT_MAP, MAP_BUILDER);
            mapBuilder = call(mapBuilder, MAP_ADD_ALL, call(item, COMPONENTS));
            mapBuilder = call(mapBuilder, MAP_SET, type, value);
            componentsField(item).set(item, call(mapBuilder, MAP_BUILD));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new UnsupportedOperationException("Retromod could not set the " + componentType[0]
                    + " component of " + item, e);
        }
    }

    private static Object component(Object item, String[] componentType) throws ReflectiveOperationException {
        Object type = staticField(item.getClass().getClassLoader(), DATA_COMPONENTS, componentType);
        return call(call(item, COMPONENTS), GET, type);
    }

    private static List<?> entries(Object item) throws ReflectiveOperationException {
        ClassLoader loader = item.getClass().getClassLoader();
        Object type = staticField(loader, DATA_COMPONENTS, ATTRIBUTE_MODIFIERS);
        Object component = call(call(item, COMPONENTS), GET, type);
        return component == null ? List.of() : (List<?>) call(component, MODIFIERS);
    }

    private static Object slotGroupFor(Object item, ClassLoader loader) throws ReflectiveOperationException {
        List<?> existing = entries(item);
        if (!existing.isEmpty()) return call(existing.get(0), ENTRY_SLOT);
        return staticField(loader, EQUIPMENT_SLOT_GROUP, MAINHAND);
    }

    private static Field componentsField(Object item) throws NoSuchFieldException {
        for (Class<?> type = item.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && matches(field.getName(), COMPONENTS_FIELD)) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException("components on " + item.getClass().getName());
    }

    private static Object staticField(ClassLoader loader, String[] owner, String[] names)
            throws ReflectiveOperationException {
        Class<?> type = load(loader, owner);
        for (String name : names) {
            try {
                return type.getField(name).get(null);
            } catch (NoSuchFieldException tryNext) {
                // Try the other namespace.
            }
        }
        throw new NoSuchFieldException(names[0] + " on " + type.getName());
    }

    private static Object callStatic(ClassLoader loader, String[] owner, String[] names)
            throws ReflectiveOperationException {
        return invoke(null, load(loader, owner), names, new Object[0]);
    }

    private static Object call(Object target, String[] names, Object... args) throws ReflectiveOperationException {
        return invoke(target, target.getClass(), names, args);
    }

    private static Object invoke(Object target, Class<?> type, String[] names, Object[] args)
            throws ReflectiveOperationException {
        List<Method> candidates = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == args.length && matches(method.getName(), names)) {
                candidates.add(method);
            }
        }
        for (Method method : candidates) {
            if (!accepts(method, args)) continue;
            // Record and lambda classes behind public interfaces are often not public themselves.
            method.trySetAccessible();
            return method.invoke(target, args);
        }
        throw new NoSuchMethodException(names[0] + " on " + type.getName());
    }

    private static boolean accepts(Method method, Object[] args) {
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && !parameters[i].isPrimitive() && !parameters[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> load(ClassLoader loader, String[] names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name, true, loader);
            } catch (ClassNotFoundException tryNext) {
                // Try the other namespace.
            }
        }
        throw new ClassNotFoundException(names[0]);
    }

    private static boolean matches(String name, String[] names) {
        for (String candidate : names) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
