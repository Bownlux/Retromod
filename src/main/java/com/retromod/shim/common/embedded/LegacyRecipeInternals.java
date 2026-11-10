/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads recipe internals that 26.1 made private or retyped, for recipe subclasses written against
 * 1.21.x.
 *
 * <p>A 1.21.x recipe wrapper copies a vanilla recipe by reading its {@code result} field, an
 * {@code ItemStack} that a Fabric access widener opened. 26.1 keeps a private
 * {@code ItemStackTemplate} under the same name, so the old field reference cannot link. This
 * reads the current field and hands back a fresh stack built from it.
 *
 * <p>No compile-time Minecraft dependency: it is copied into each mod that needs it, and the
 * Minecraft side is reached reflectively. 26.1 and newer ship unobfuscated, so the field and
 * method names are stable on every loader.
 */
public final class LegacyRecipeInternals {

    private static final Map<Class<?>, Field> RESULT_FIELDS = new ConcurrentHashMap<>();

    private LegacyRecipeInternals() {
    }

    /**
     * The result stack the 1.21.x {@code result} field held for {@code recipe}.
     *
     * @throws IllegalStateException naming the recipe class when the field cannot be read
     */
    public static Object result(Object recipe) {
        if (recipe == null) {
            throw new NullPointerException("recipe");
        }
        Field field = RESULT_FIELDS.computeIfAbsent(recipe.getClass(),
                LegacyRecipeInternals::findResultField);
        if (field == null) {
            throw new IllegalStateException("Retromod: " + recipe.getClass().getName()
                    + " has no recipe result field on this Minecraft version");
        }
        try {
            Object value = field.get(recipe);
            if (value == null || "net.minecraft.world.item.ItemStack".equals(
                    value.getClass().getName())) {
                return value;
            }
            // ItemStackTemplate.create() builds a new stack, which is what reading the old
            // mutable field and copying it amounted to.
            Method create = LegacyPublicMethods.method(value.getClass(), "create");
            return create.invoke(value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod: could not read the result of "
                    + recipe.getClass().getName(), e);
        }
    }

    /**
     * The 26.1 {@code ItemStackTemplate} behind {@code recipe}'s result, for a copy that passes it
     * straight back to a recipe constructor. Recipes decode before item components are bound, so
     * building an {@code ItemStack} there would fail; the template needs no components.
     */
    public static Object resultTemplate(Object recipe) {
        if (recipe == null) {
            throw new NullPointerException("recipe");
        }
        Field field = RESULT_FIELDS.computeIfAbsent(recipe.getClass(),
                LegacyRecipeInternals::findResultField);
        if (field == null) {
            throw new IllegalStateException("Retromod: " + recipe.getClass().getName()
                    + " has no recipe result field on this Minecraft version");
        }
        try {
            return field.get(recipe);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod: could not read the result of "
                    + recipe.getClass().getName(), e);
        }
    }

    /** Whether an ingredient is empty; one that names a tag is not, though recipes load before tags are bound. */
    private static boolean isEmpty(Object ingredient) throws ReflectiveOperationException {
        try {
            Field values = ingredient.getClass().getDeclaredField("values");
            values.setAccessible(true);
            Object set = values.get(ingredient);
            Class<?> named = Class.forName("net.minecraft.core.HolderSet$Named", true, LegacyRecipeInternals.class.getClassLoader());
            if (named.isInstance(set) && !(boolean) named.getMethod("isBound").invoke(set)) return false;
        } catch (NoSuchFieldException otherShape) {
            // An ingredient without a holder set answers for itself.
        }
        return (Boolean) LegacyPublicMethods.method(ingredient.getClass(), "isEmpty").invoke(ingredient);
    }

    /**
     * The 26.1 pattern for 1.20.1's {@code (width, height, ingredients)}: a grid in which an empty
     * ingredient leaves its slot empty.
     */
    public static Object legacyPattern(int width, int height, Object ingredients) {
        try {
            ClassLoader loader = LegacyRecipeInternals.class.getClassLoader();
            java.util.List<java.util.Optional<Object>> slots = new java.util.ArrayList<>();
            for (Object ingredient : (Iterable<?>) ingredients) {
                boolean empty = ingredient == null || isEmpty(ingredient);
                slots.add(empty ? java.util.Optional.empty() : java.util.Optional.of(ingredient));
            }
            return Class.forName("net.minecraft.world.item.crafting.ShapedRecipePattern", true, loader)
                    .getConstructor(int.class, int.class, java.util.List.class, java.util.Optional.class)
                    .newInstance(width, height, slots, java.util.Optional.empty());
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod: could not build a " + width + " by " + height + " recipe pattern", e);
        }
    }

    /** The crafting grid a 1.20.1 recipe reads, holding the stacks of the input 26.1 hands it. */
    public static Object craftingContainer(Object input) {
        try {
            ClassLoader loader = LegacyRecipeInternals.class.getClassLoader();
            int width = (int) invoke(input, "width");
            int height = (int) invoke(input, "height");
            java.util.List<?> stacks = (java.util.List<?>) invoke(input, "items");
            Class<?> grid = Class.forName("net.minecraft.world.inventory.TransientCraftingContainer", true, loader);
            Class<?> list = Class.forName("net.minecraft.core.NonNullList", true, loader);
            // The constructor that takes the stacks is private; setItem would tell a menu, and this grid has none.
            java.lang.reflect.Constructor<?> withItems = grid.getDeclaredConstructor(
                    Class.forName("net.minecraft.world.inventory.AbstractContainerMenu", true, loader), int.class, int.class, list);
            withItems.setAccessible(true);
            Object empty = Class.forName("net.minecraft.world.item.ItemStack", true, loader).getField("EMPTY").get(null);
            Object items = list.getMethod("of", Object.class, Object[].class).invoke(null, empty, stacks.toArray());
            return withItems.newInstance(null, width, height, items);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod: could not hand a crafting grid to an old recipe", e);
        }
    }

    /** A null group would fail the recipe book codec; the 1.21.x default was the empty string. */
    public static String group(String group) {
        return group == null ? "" : group;
    }

    /**
     * The running game's registries, for an old {@code assemble(input, registries)} override.
     * 26.1 calls {@code assemble(input)} with no registries, so they are taken from the server
     * that is crafting, or from the client's level. Returns null only when no game is running,
     * which is what the override received before this lookup existed.
     */
    public static Object registries() {
        ClassLoader loader = LegacyRecipeInternals.class.getClassLoader();
        Object game = invokeStatic(loader, "net.fabricmc.loader.api.FabricLoader", "getInstance");
        game = game == null ? null : invoke(game, "getGameInstance");
        if (game == null) {
            game = invokeStatic(loader, "net.neoforged.neoforge.server.ServerLifecycleHooks",
                    "getCurrentServer");
        }
        if (game == null) {
            game = invokeStatic(loader, "net.minecraft.client.Minecraft", "getInstance");
        }
        if (game == null) {
            return null;
        }
        Object registries = invoke(game, "registryAccess");
        if (registries != null) {
            return registries;
        }
        // The client has no registries of its own. In singleplayer the integrated server does the
        // crafting, so its registries come first. A multiplayer client only has its level's.
        Object server = invoke(game, "getSingleplayerServer");
        registries = server == null ? null : invoke(server, "registryAccess");
        if (registries != null) {
            return registries;
        }
        Object level = readField(game, "level");
        return level == null ? null : invoke(level, "registryAccess");
    }

    private static Object invokeStatic(ClassLoader loader, String className, String method) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            return type.getMethod(method).invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
            return null;
        }
    }

    private static Object invoke(Object receiver, String method) {
        try {
            return LegacyPublicMethods.method(receiver.getClass(), method).invoke(receiver);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
            return null;
        }
    }

    private static Object readField(Object receiver, String name) {
        try {
            return receiver.getClass().getField(name).get(receiver);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
            return null;
        }
    }

    private static Field findResultField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.getName().startsWith("net.minecraft.")) {
                continue;
            }
            try {
                Field field = c.getDeclaredField("result");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {
                // Keep walking: the field lives on the vanilla recipe class, not its parents.
            } catch (RuntimeException inaccessible) {
                return null;
            }
        }
        return null;
    }
}
