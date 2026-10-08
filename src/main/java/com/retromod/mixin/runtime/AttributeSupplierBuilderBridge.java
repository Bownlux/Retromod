/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/**
 * Stands in for the {@code AttributeSupplier.Builder.builder} map that 1.20.5 retyped.
 *
 * <p>Before 1.20.5 the builder collected its attribute instances in a mutable
 * {@code Map<Attribute, AttributeInstance>}, and mods reached it with an {@code @Accessor} to
 * merge attributes into another entity's defaults. Since then the field is a Guava
 * {@code ImmutableMap.Builder} keyed by attribute holders, and {@code build()} keeps the last
 * value for a repeated key. {@link com.retromod.mixin.MixinAccessorFieldMigration} points calls of
 * the old accessor here.
 *
 * <p>{@link #builder} returns a map view of that builder. A put adds the entry to the builder, and
 * reading the view sees what the builder would build now. The keys are whatever the mod passes:
 * a mod that copies another supplier's instances passes holders, which is what the host expects.
 *
 * <p>No compile-time Minecraft or Guava dependency, for the same per-mod embedding reason as
 * {@link AreaEffectCloudEffectsBridge}. When the builder field cannot be found the view is
 * detached, so the mod's additions are lost instead of crashing its setup.
 */
public final class AttributeSupplierBuilderBridge {

    private static final String GUAVA_BUILDER = "com.google.common.collect.ImmutableMap$Builder";

    private AttributeSupplierBuilderBridge() {}

    /** A map view that writes into the attribute builder's Guava builder. */
    public static Object builder(Object attributeBuilder) {
        Object guavaBuilder = findGuavaBuilder(attributeBuilder);
        return guavaBuilder == null ? new java.util.HashMap<>() : new BuilderView(guavaBuilder);
    }

    private static Object findGuavaBuilder(Object owner) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!GUAVA_BUILDER.equals(field.getType().getName())) continue;
                try {
                    field.setAccessible(true);
                    return field.get(owner);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static final class BuilderView extends AbstractMap<Object, Object> {
        private final Object builder;

        BuilderView(Object builder) {
            this.builder = builder;
        }

        /** Always returns null: building a snapshot per put would cost more than the merge. */
        @Override
        public Object put(Object key, Object value) {
            invoke("put", new Class<?>[] {Object.class, Object.class}, key, value);
            return null;
        }

        @Override
        public Set<Entry<Object, Object>> entrySet() {
            return snapshot().entrySet();
        }

        /** What the builder holds now. Repeated keys resolve the way the game's build does. */
        @SuppressWarnings("unchecked")
        private Map<Object, Object> snapshot() {
            Object built = invoke("buildKeepingLast", new Class<?>[0]);
            return built instanceof Map<?, ?> map ? (Map<Object, Object>) map : Map.of();
        }

        private Object invoke(String name, Class<?>[] parameters, Object... arguments) {
            try {
                Method method = builder.getClass().getMethod(name, parameters);
                return method.invoke(builder, arguments);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new UnsupportedOperationException(
                        "Retromod could not reach the attribute builder's " + name + " method", e);
            }
        }
    }
}
