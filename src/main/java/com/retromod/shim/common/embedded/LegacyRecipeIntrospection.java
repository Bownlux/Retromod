/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Answers the recipe questions 1.21.1 mods ask, which 1.21.2 moved to the recipe map, placement info, and displays. */
public final class LegacyRecipeIntrospection {

    private static final String CRAFTING = "net.minecraft.world.item.crafting.";

    private LegacyRecipeIntrospection() {}

    // The empty ingredient, built once.
    private static volatile Object emptyIngredient;

    private static Class<?> idClass(ClassLoader loader) throws ClassNotFoundException {
        try {
            return Class.forName("net.minecraft.resources.Identifier", true, loader);
        } catch (ClassNotFoundException beforeOneTwentyOneEleven) {
            return Class.forName("net.minecraft.resources.ResourceLocation", true, loader);
        }
    }

    private static Object id(String namespace, String path) throws ReflectiveOperationException {
        return idClass(loader()).getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
    }


    /** {@code manager.getAllRecipesFor(type)}: the recipe map's holders of that type. */
    public static Object allRecipesFor(Object manager, Object type) {
        if (hasMethod(manager, "recipeMap")) {
            return new ArrayList<>((Collection<?>) call(call(manager, "recipeMap"), "byType", type));
        }
        // Forge and Fabric keep the map private; getRecipes() lists the same holders in the map's order.
        List<Object> holders = new ArrayList<>();
        Map<Class<?>, Method> values = new HashMap<>();
        Map<Class<?>, Method> types = new HashMap<>();
        for (Object holder : allRecipes(manager)) {
            Object recipe = callCached(values, holder, "value");
            if (type.equals(callCached(types, recipe, "getType"))) holders.add(holder);
        }
        return holders;
    }

    /** {@code manager.getRecipesFor(type, input, level)}. */
    public static Object recipesFor(Object manager, Object type, Object input, Object level) {
        if (hasMethod(manager, "recipeMap")) {
            return ((Stream<?>) call(call(manager, "recipeMap"), "getRecipesFor", type, input, level)).toList();
        }
        // As RecipeMap.getRecipesFor does, an empty input matches nothing.
        List<Object> matches = new ArrayList<>();
        if ((Boolean) call(input, "isEmpty")) return matches;
        Map<Class<?>, Method> values = new HashMap<>();
        Map<Class<?>, Method> matchers = new HashMap<>();
        for (Object holder : (List<?>) allRecipesFor(manager, type)) {
            Object recipe = callCached(values, holder, "value");
            if ((Boolean) callCached(matchers, recipe, "matches", input, level)) matches.add(holder);
        }
        return matches;
    }

    /** {@code manager.getRecipes()}, taking the overload that returns a collection. */
    private static Collection<?> allRecipes(Object manager) {
        // Fabric API merges a getRecipes() accessor that returns the RecipeMap, so the name alone can pick the wrong one.
        for (Method method : LegacyPublicMethods.methods(manager.getClass())) {
            if (method.getName().equals("getRecipes") && method.getParameterCount() == 0
                    && Collection.class.isAssignableFrom(method.getReturnType())) {
                return (Collection<?>) invoke(method, manager);
            }
        }
        throw new IllegalStateException("Retromod could not list recipes: " + manager.getClass().getName()
                + " has neither recipeMap() nor a getRecipes() that returns a collection."
                + " Report it to Retromod with your loader and Minecraft version.");
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

    /**
     * {@code recipe.getIngredients()}: a shaped recipe's grid with its empty slots, as 1.21.1 listed it, else the
     * ingredients its placement info lists, none for a special recipe.
     */
    public static Object ingredients(Object recipe) {
        try {
            List<?> ingredients = grid(recipe);
            if (ingredients == null) ingredients = (List<?>) call(call(recipe, "placementInfo"), "ingredients");
            // NonNullList.copyOf is gone from 26.1, so the copy starts empty and takes the ingredients.
            @SuppressWarnings("unchecked")
            List<Object> copy = (List<Object>) Class.forName("net.minecraft.core.NonNullList", true, loader()).getMethod("create").invoke(null);
            copy.addAll(ingredients);
            return copy;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The slots of a shaped recipe's grid, an empty ingredient in each empty one; null for a recipe without a grid. */
    private static List<?> grid(Object recipe) {
        for (Method method : LegacyPublicMethods.methods(recipe.getClass())) {
            if (!method.getName().equals("getIngredients") || method.getParameterCount() != 0
                    || !List.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            List<Object> slots = new ArrayList<>();
            for (Object slot : (List<?>) invoke(method, recipe)) {
                if (slot instanceof java.util.Optional<?> optional) {
                    slots.add(optional.isPresent() ? optional.get() : emptyIngredient());
                } else {
                    slots.add(slot);
                }
            }
            return slots;
        }
        return null;
    }

    /** {@code ingredient.isEmpty()} as 1.20.1 answered it: an ingredient that names a tag has a value, bound or not. */
    public static boolean ingredientIsEmpty(Object ingredient) {
        try {
            // 1.20.1 counted a named tag as a value whether it held items or not; the current isEmpty counts the tag's items.
            if (namesTag(ingredient)) return false;
            return (boolean) LegacyPublicMethods.method(ingredient.getClass(), "isEmpty").invoke(ingredient);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not ask whether an ingredient is empty", e);
        }
    }

    private static boolean namesTag(Object ingredient) throws ReflectiveOperationException {
        java.lang.reflect.Field values;
        try {
            values = ingredient.getClass().getDeclaredField("values");
        } catch (NoSuchFieldException otherShape) {
            return false;
        }
        values.setAccessible(true);
        Object set = values.get(ingredient);
        Class<?> named = Class.forName("net.minecraft.core.HolderSet$Named", true, loader());
        if (!named.isInstance(set)) return false;
        // Retromod's own stand-in for the removed Ingredient.EMPTY is a named tag that must still read as empty.
        Object key = named.getMethod("key").invoke(set);
        Object id = key.getClass().getMethod("location").invoke(key);
        return !"retromod:legacy_empty".equals(String.valueOf(id));
    }

    /** {@code Ingredient.EMPTY}, which 1.21.2 removed: an ingredient that accepts nothing. */
    public static Object emptyIngredient() {
        Object empty = emptyIngredient;
        if (empty != null) return empty;
        try {
            ClassLoader loader = loader();
            // A tag of no items, since an ingredient refuses an empty list of items.
            Class<?> named = Class.forName("net.minecraft.core.HolderSet$Named", true, loader);
            Class<?> owner = Class.forName("net.minecraft.core.HolderOwner", true, loader);
            Class<?> tagKey = Class.forName("net.minecraft.tags.TagKey", true, loader);
            Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey", true, loader);
            Object items = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader).getField("ITEM").get(null);
            Object itemKey = Class.forName("net.minecraft.core.registries.Registries", true, loader).getField("ITEM").get(null);
            Object key = tagKey.getMethod("create", resourceKey, idClass(loader)).invoke(null, itemKey, id("retromod", "legacy_empty"));
            Constructor<?> constructor = named.getDeclaredConstructor(owner, tagKey);
            constructor.setAccessible(true);
            Object set = constructor.newInstance(items, key);
            Method bind = named.getDeclaredMethod("bind", List.class);
            bind.setAccessible(true);
            bind.invoke(set, List.of());
            empty = Class.forName(CRAFTING + "Ingredient", true, loader)
                    .getMethod("of", Class.forName("net.minecraft.core.HolderSet", true, loader)).invoke(null, set);
            emptyIngredient = empty;
            return empty;
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod could not build an empty ingredient", e);
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
            List<?> holders;
            try {
                holders = ((Stream<?>) call(ingredient, "items")).toList();
            } catch (IllegalStateException unbound) {
                // A tag ingredient read before a world binds tags; 1.20.1 read the unloaded tag as empty.
                if (unbound.getMessage() == null || !unbound.getMessage().contains("unbound tag")) throw unbound;
                return Array.newInstance(stack, 0);
            }
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
        return invoke(find(target, name, arguments), target, arguments);
    }

    /** Like call, but finds each class's method once for a whole loop over the recipes. */
    private static Object callCached(Map<Class<?>, Method> found, Object target, String name, Object... arguments) {
        Method method = found.get(target.getClass());
        if (method == null) {
            method = find(target, name, arguments);
            found.put(target.getClass(), method);
        }
        return invoke(method, target, arguments);
    }

    /** The public method a call by that name with these arguments runs. */
    private static Method find(Object target, String name, Object... arguments) {
        for (Method method : LegacyPublicMethods.methods(target.getClass())) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            boolean fits = true;
            Class<?>[] parameters = method.getParameterTypes();
            for (int i = 0; i < parameters.length && fits; i++) {
                fits = arguments[i] == null || parameters[i].isPrimitive() || parameters[i].isInstance(arguments[i]);
            }
            if (fits) return method;
        }
        throw new IllegalStateException(target.getClass().getName() + " has no " + name);
    }

    /** Whether the target has a public no-argument method by that name. */
    private static boolean hasMethod(Object target, String name) {
        for (Method method : LegacyPublicMethods.methods(target.getClass())) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) return true;
        }
        return false;
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            method.trySetAccessible();
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
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
