/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for the {@code AreaEffectCloud.effects} list that 1.20.5 removed.
 *
 * <p>Before 1.20.5 the cloud kept its custom effects in a mutable {@code List<MobEffectInstance>}
 * field, and mods reached it with an {@code @Accessor}. Since then the cloud holds an immutable
 * {@code PotionContents} record whose {@code customEffects} component carries the same list, and
 * the record is swapped through {@code setPotionContents}, which also refreshes the cloud color.
 * {@link com.retromod.mixin.MixinAccessorFieldMigration} points calls of the old accessor here.
 *
 * <p>{@link #effects} returns a list that writes every change back as a new {@code PotionContents}
 * built from the current one, so code that clears or appends to the old list still changes what
 * the cloud applies. The potion, color, and any other component are copied unchanged.
 *
 * <p>No compile-time Minecraft dependency: Retromod builds without Minecraft on the classpath,
 * and a per-mod copy of this class must load with only what the mod's module can see. Every
 * Minecraft type is therefore reached reflectively. A reflection failure leaves the cloud as it
 * was and hands back a detached list, so the mod's change is lost instead of crashing the tick.
 */
public final class AreaEffectCloudEffectsBridge {

    private static final String POTION_CONTENTS = "net.minecraft.world.item.alchemy.PotionContents";
    private static final String CUSTOM_EFFECTS = "customEffects";

    private AreaEffectCloudEffectsBridge() {}

    /**
     * The cloud's custom effects, as a list that writes changes back to the cloud. Typed
     * {@code Object} so the call site's descriptor needs no Minecraft or collection types.
     */
    public static Object effects(Object cloud) {
        Object contents = readContents(cloud);
        if (contents == null) return new ArrayList<>();
        try {
            return new WriteThroughEffects(cloud, customEffects(contents));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /** Replaces the cloud's custom effects, keeping its potion and color. */
    public static void setEffects(Object cloud, Object effects) {
        writeEffects(cloud, effects instanceof List<?> list ? list : List.of());
    }

    static final class WriteThroughEffects extends AbstractList<Object> {
        private final Object cloud;
        private final List<Object> current;

        WriteThroughEffects(Object cloud, List<?> initial) {
            this.cloud = cloud;
            this.current = new ArrayList<>(initial);
        }

        @Override
        public Object get(int index) {
            return current.get(index);
        }

        @Override
        public int size() {
            return current.size();
        }

        @Override
        public Object set(int index, Object element) {
            Object previous = current.set(index, element);
            writeEffects(cloud, current);
            return previous;
        }

        @Override
        public void add(int index, Object element) {
            current.add(index, element);
            modCount++;
            writeEffects(cloud, current);
        }

        @Override
        public Object remove(int index) {
            Object removed = current.remove(index);
            modCount++;
            writeEffects(cloud, current);
            return removed;
        }

        @Override
        public void clear() {
            current.clear();
            modCount++;
            writeEffects(cloud, current);
        }
    }

    static void writeEffects(Object cloud, List<?> effects) {
        Object contents = readContents(cloud);
        if (contents == null) return;
        try {
            Object updated = withCustomEffects(contents, List.copyOf(effects));
            Method setter = findSetter(cloud.getClass(), contents.getClass());
            if (setter != null) setter.invoke(cloud, updated);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The cloud keeps its previous effects; see the class comment.
        }
    }

    private static Object readContents(Object cloud) {
        if (cloud == null) return null;
        for (Class<?> type = cloud.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!POTION_CONTENTS.equals(field.getType().getName())) continue;
                try {
                    field.setAccessible(true);
                    return field.get(cloud);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static List<?> customEffects(Object contents) throws ReflectiveOperationException {
        RecordComponent component = effectsComponent(contents.getClass());
        Object value = component.getAccessor().invoke(contents);
        return value instanceof List<?> list ? list : List.of();
    }

    private static Object withCustomEffects(Object contents, List<?> effects)
            throws ReflectiveOperationException {
        RecordComponent[] components = contents.getClass().getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        RecordComponent target = effectsComponent(contents.getClass());
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = components[i].getName().equals(target.getName())
                    ? effects : components[i].getAccessor().invoke(contents);
        }
        Constructor<?> canonical = contents.getClass().getDeclaredConstructor(types);
        canonical.setAccessible(true);
        return canonical.newInstance(values);
    }

    /** The {@code customEffects} component, or the only list-typed one if it was renamed. */
    private static RecordComponent effectsComponent(Class<?> recordType) throws NoSuchFieldException {
        RecordComponent[] components = recordType.getRecordComponents();
        if (components == null) throw new NoSuchFieldException(recordType.getName() + " is not a record");
        RecordComponent onlyList = null;
        int lists = 0;
        for (RecordComponent component : components) {
            if (CUSTOM_EFFECTS.equals(component.getName())) return component;
            if (List.class.isAssignableFrom(component.getType())) {
                onlyList = component;
                lists++;
            }
        }
        if (lists == 1) return onlyList;
        throw new NoSuchFieldException("no custom effects component on " + recordType.getName());
    }

    private static Method findSetter(Class<?> cloudType, Class<?> contentsType) {
        Method fallback = null;
        for (Class<?> type = cloudType; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != contentsType
                        || method.getReturnType() != void.class) {
                    continue;
                }
                if ("setPotionContents".equals(method.getName())) return accessible(method);
                if (fallback == null) fallback = method;
            }
        }
        return fallback == null ? null : accessible(fallback);
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }
}
