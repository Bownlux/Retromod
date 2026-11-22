/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

/** {@code CycleButton.builder(stringifier)} and {@code withInitialValue}, built on the builder that takes its initial value up front. */
public final class LegacyCycleButtons {

    // The initial value each builder from builder(Function) reads, so a later withInitialValue can still set it.
    private static final Map<Object, InitialValue<?>> INITIAL_VALUES = new WeakHashMap<>();

    private LegacyCycleButtons() {}

    /** {@code CycleButton.builder(stringifier)}: the current builder takes its initial value up front, so it reads a holder. */
    public static <T> CycleButton.Builder<T> builder(Function<T, Component> stringifier) {
        InitialValue<T> initial = new InitialValue<>();
        CycleButton.Builder<T> builder = new CycleButton.Builder<>(stringifier, initial);
        initial.builder = new WeakReference<>(builder);
        synchronized (INITIAL_VALUES) {
            INITIAL_VALUES.put(builder, initial);
        }
        return builder;
    }

    /** {@code CycleButton.booleanBuilder(on, off)}: the current one takes the initial value up front, so it starts true until set. */
    public static CycleButton.Builder<Boolean> booleanBuilder(Component on, Component off) {
        InitialValue<Boolean> initial = new InitialValue<>();
        initial.value = Boolean.TRUE;
        CycleButton.Builder<Boolean> builder = new CycleButton.Builder<Boolean>(value -> value ? on : off, initial);
        synchronized (INITIAL_VALUES) {
            INITIAL_VALUES.put(builder, initial);
        }
        return builder.withValues(java.util.List.of(Boolean.TRUE, Boolean.FALSE));
    }

    /** {@code builder.withInitialValue(value)}: fills the holder of a builder made by {@link #builder}. */
    @SuppressWarnings("unchecked")
    public static <T> CycleButton.Builder<T> withInitialValue(CycleButton.Builder<T> builder, T value) {
        InitialValue<T> initial;
        synchronized (INITIAL_VALUES) {
            initial = (InitialValue<T>) INITIAL_VALUES.get(builder);
        }
        // A builder the host made already has its initial value, which the old call cannot replace.
        if (initial != null) initial.value = value;
        return builder;
    }

    /** The first value the builder offers, where the old builder started without an initial value; null if unreadable. */
    static Object firstDefaultValue(Object builder) {
        if (builder == null) return null;
        // The value list is found by its type's getDefaultList(), so a renamed field still matches.
        for (Field field : builder.getClass().getDeclaredFields()) {
            Method defaults;
            try {
                defaults = field.getType().getMethod("getDefaultList");
            } catch (NoSuchMethodException notTheValueList) {
                continue;
            }
            if (!List.class.isAssignableFrom(defaults.getReturnType())) continue;
            try {
                field.setAccessible(true);
                Object supplier = field.get(builder);
                if (supplier == null) return null;
                List<?> list = (List<?>) defaults.invoke(supplier);
                return list == null || list.isEmpty() ? null : list.get(0);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    private static final class InitialValue<T> implements Supplier<T> {
        private volatile T value;
        // Weak, since the builder holds this holder and the map above must still let the builder go.
        private volatile WeakReference<Object> builder;

        @Override
        @SuppressWarnings("unchecked")
        public T get() {
            T current = value;
            if (current != null) return current;
            // Without withInitialValue the old builder started on its first value.
            WeakReference<Object> owner = builder;
            current = (T) firstDefaultValue(owner == null ? null : owner.get());
            if (current != null) value = current;
            return current;
        }
    }
}
