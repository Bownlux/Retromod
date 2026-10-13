/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Stands in for the loot condition fields that 26.3 rebuilt around holders.
 *
 * <p>26.3 replaced {@code LootPoolEntryContainer.conditions} ({@code List<LootItemCondition>}) with
 * one optional {@code Holder} named {@code condition}, {@code CompositeLootItemCondition.terms} with
 * a {@code HolderSet}, and {@code InvertedLootItemCondition.term} with a {@code Holder}. An old
 * accessor for any of them failed its whole mixin. These methods read the new fields and hand back
 * the old shapes, so old code sees the same conditions it used to, and the new fields stay as they
 * are for the game and for newer mods. {@link com.retromod.mixin.MixinAccessorFieldMigration}
 * points the old accessors here.
 *
 * <p>Reflection is used because this class is embedded into mods and compiled without Minecraft.
 * The names are Mojang's, which every 26.3 host uses at runtime.
 */
public final class LootConditionFieldsBridge {
    private LootConditionFieldsBridge() {}

    /** {@code LootPoolEntryContainer.conditions}: the entry's one condition as a list, or empty. */
    public static Object conditions(Object entry) {
        Optional<?> condition = (Optional<?>) read(entry, "condition");
        return condition.isPresent() ? List.of(holderValue(condition.get())) : List.of();
    }

    /** {@code CompositeLootItemCondition.terms}: every term of the holder set, in order. */
    public static Object terms(Object composite) {
        List<Object> terms = new ArrayList<>();
        for (Object holder : (Iterable<?>) read(composite, "terms")) {
            terms.add(holderValue(holder));
        }
        return List.copyOf(terms);
    }

    /**
     * Replaces the terms of a composite condition, and rebuilds the predicate it tests with, since
     * 26.3 computes that predicate once from the terms when the condition is built.
     */
    public static void setTerms(Object composite, Object terms) {
        try {
            ClassLoader loader = composite.getClass().getClassLoader();
            Class<?> holderType = Class.forName("net.minecraft.core.Holder", false, loader);
            Class<?> holderSetType = Class.forName("net.minecraft.core.HolderSet", false, loader);
            Method directHolder = holderType.getMethod("direct", Object.class);
            List<Object> holders = new ArrayList<>();
            for (Object term : (List<?>) terms) {
                holders.add(directHolder.invoke(null, term));
            }
            Object holderSet = holderSetType.getMethod("direct", List.class).invoke(null, holders);
            Field termsField = field(composite.getClass(), "terms");
            Method lazy = termsField.getDeclaringClass().getDeclaredMethod("holdersToLazyPredicates", holderSetType);
            lazy.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Predicate<Object>> predicates = (List<Predicate<Object>>) lazy.invoke(null, holderSet);
            boolean anyOf = composite.getClass().getSimpleName().contains("AnyOf");
            Predicate<Object> composed = context -> anyOf
                    ? predicates.stream().anyMatch(p -> p.test(context))
                    : predicates.stream().allMatch(p -> p.test(context));
            termsField.set(composite, holderSet);
            field(composite.getClass(), "composedPredicate").set(composite, composed);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not set the terms of " + composite.getClass().getName()
                    + " on this Minecraft version", e);
        }
    }

    /** {@code InvertedLootItemCondition.term}: the condition behind the holder. */
    public static Object term(Object inverted) {
        return holderValue(read(inverted, "term"));
    }

    private static Object holderValue(Object holder) {
        try {
            Class<?> holderType = Class.forName("net.minecraft.core.Holder", false, holder.getClass().getClassLoader());
            return holderType.getMethod("value").invoke(holder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read a loot condition holder", e);
        }
    }

    private static Object read(Object owner, String name) {
        try {
            return field(owner.getClass(), name).get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read " + name + " from "
                    + owner.getClass().getName() + " on this Minecraft version", e);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException next) {
                // Keep walking up: the field is declared on an abstract parent.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
