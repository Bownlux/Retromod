/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/** Answers the recipe questions 1.21.1 mods ask, which 1.21.2 moved to the recipe map, placement info, and displays. */
public final class LegacyRecipeIntrospection {

    private static final String CRAFTING = "net.minecraft.world.item.crafting.";

    private LegacyRecipeIntrospection() {}

    /** {@code manager.getAllRecipesFor(type)}: the recipe map's holders of that type. */
    public static Object allRecipesFor(Object manager, Object type) {
        Object map = call(manager, "recipeMap");
        return new ArrayList<>((Collection<?>) call(map, "byType", type));
    }

    /** {@code manager.getRecipesFor(type, input, level)}. */
    public static Object recipesFor(Object manager, Object type, Object input, Object level) {
        Object map = call(manager, "recipeMap");
        return ((Stream<?>) call(map, "getRecipesFor", type, input, level)).toList();
    }

    /** {@code manager.byKey(id)}: a recipe is keyed by a resource key now. */
    public static Object byKey(Object manager, Object id) {
        try {
            ClassLoader loader = loader();
            Class<?> keyClass = Class.forName("net.minecraft.resources.ResourceKey", true, loader);
            Object recipes = Class.forName("net.minecraft.core.registries.Registries", true, loader).getField("RECIPE").get(null);
            Object key = keyClass.getMethod("create", keyClass, id.getClass()).invoke(null, recipes, id);
            return call(manager, "byKey", key);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not look up the recipe " + id, failure);
        }
    }

    /** {@code holder.id()}, which returned the recipe's id before it returned its key. */
    public static Object holderId(Object holder) {
        return call(call(holder, "id"), "identifier");
    }

    /** {@code recipe.getIngredients()}: the ingredients its placement info lists, none for a special recipe. */
    public static Object ingredients(Object recipe) {
        try {
            List<?> ingredients = (List<?>) call(call(recipe, "placementInfo"), "ingredients");
            return Class.forName("net.minecraft.core.NonNullList", true, loader()).getMethod("copyOf", Collection.class)
                    .invoke(null, ingredients);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** {@code recipe.getResultItem(registries)}: what its first display shows as the result. */
    public static Object resultItem(Object recipe, Object registries) {
        try {
            ClassLoader loader = loader();
            Class<?> stack = Class.forName("net.minecraft.world.item.ItemStack", true, loader);
            List<?> displays = (List<?>) call(recipe, "display");
            if (displays.isEmpty()) return stack.getField("EMPTY").get(null);
            Class<?> context = Class.forName(CRAFTING + "display.SlotDisplayContext", true, loader);
            Object map = displayContext(context, registries, loader);
            return call(call(displays.get(0), "result"), "resolveForFirstStack", map);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not read a recipe's result", failure);
        }
    }

    /** The context a slot display resolves in, holding the registries when there are any. */
    private static Object displayContext(Class<?> context, Object registries, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> contextMap = Class.forName("net.minecraft.util.context.ContextMap", true, loader);
        Class<?> builderClass = Class.forName("net.minecraft.util.context.ContextMap$Builder", true, loader);
        Class<?> key = Class.forName("net.minecraft.util.context.ContextKey", true, loader);
        Class<?> keySet = Class.forName("net.minecraft.util.context.ContextKeySet", true, loader);
        Object keys = context.getField("CONTEXT").get(null);
        Object registriesKey = context.getField("REGISTRIES").get(null);
        // 26.3 gets the builder from ContextMap.builder(), sets entries with set, and builds with
        // buildAndValidate; before it the builder was constructed directly.
        try {
            Object builder = contextMap.getMethod("builder").invoke(null);
            if (registries != null) builder = builderClass.getMethod("set", key, Object.class).invoke(builder, registriesKey, registries);
            return builderClass.getMethod("buildAndValidate", keySet).invoke(builder, keys);
        } catch (NoSuchMethodException olderBuilder) {
            Object builder = builderClass.getConstructor().newInstance();
            if (registries != null) builder = builderClass.getMethod("withParameter", key, Object.class).invoke(builder, registriesKey, registries);
            return builderClass.getMethod("create", keySet).invoke(builder, keys);
        }
    }

    /** {@code ingredient.getItems()}: one stack for each item it accepts. */
    public static Object items(Object ingredient) {
        try {
            Class<?> stack = Class.forName("net.minecraft.world.item.ItemStack", true, loader());
            Class<?> holder = Class.forName("net.minecraft.core.Holder", true, loader());
            List<?> holders = ((Stream<?>) call(ingredient, "items")).toList();
            Object stacks = Array.newInstance(stack, holders.size());
            for (int i = 0; i < holders.size(); i++) Array.set(stacks, i, stack.getConstructor(holder).newInstance(holders.get(i)));
            return stacks;
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object call(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            boolean fits = true;
            Class<?>[] parameters = method.getParameterTypes();
            for (int i = 0; i < parameters.length && fits; i++) {
                fits = arguments[i] == null || parameters[i].isPrimitive() || parameters[i].isInstance(arguments[i]);
            }
            if (!fits) continue;
            try {
                method.trySetAccessible();
                return method.invoke(target, arguments);
            } catch (InvocationTargetException e) {
                throw rethrow(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException(target.getClass().getName() + " has no " + name);
    }

    private static ClassLoader loader() {
        return LegacyRecipeIntrospection.class.getClassLoader();
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }
}
