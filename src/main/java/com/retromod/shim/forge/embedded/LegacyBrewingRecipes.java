/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Forge's static {@code BrewingRecipeRegistry}, rebuilt on the brewing event of Forge 26.2 and of NeoForge. */
public final class LegacyBrewingRecipes {

    private static final String FORGE_EVENT = "net.minecraftforge.event.brewing.BrewingRecipeRegisterEvent";
    private static final String FORGE_BUS = "net.minecraftforge.eventbus.api.bus.EventBus";
    private static final String FORGE_SERVER_HOOKS = "net.minecraftforge.server.ServerLifecycleHooks";
    private static final String NEOFORGE = "net.neoforged.neoforge.common.NeoForge";
    private static final String NEOFORGE_BUS = "net.neoforged.bus.api.IEventBus";
    private static final String NEOFORGE_EVENT = "net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent";
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String STACK = "net.minecraft.world.item.ItemStack";

    // Forge kept one list that mods filled during setup. The host rebuilds brewing for every server
    // and client connection, so the recipes are kept here, in call order, and handed to each rebuild.
    private static final List<Object[]> RECIPES = new ArrayList<>();
    // Lookups by class, then by name and argument count. No nested class: this class is copied into mods alone.
    private static final Map<Class<?>, Map<String, Method>> METHODS = new ConcurrentHashMap<>();
    private static boolean listening;

    private LegacyBrewingRecipes() {}

    /** {@code addRecipe(IBrewingRecipe)}. Forge returned false only for a rejected recipe. */
    public static boolean addRecipe(Object recipe) {
        remember(new Object[] {recipe});
        return true;
    }

    /** {@code addRecipe(Ingredient input, Ingredient ingredient, ItemStack output)}. */
    public static boolean addRecipe(Object input, Object ingredient, Object output) {
        remember(new Object[] {input, ingredient, output});
        return true;
    }

    private static synchronized void remember(Object[] recipe) {
        RECIPES.add(recipe);
        if (listening) return;
        ClassLoader loader = LegacyBrewingRecipes.class.getClassLoader();
        Consumer<Object> replay = LegacyBrewingRecipes::replay;
        try {
            // Forge 26.2 posts its brewing event on the event's own bus; NeoForge posts it on the game bus.
            Class<?> forgeEvent = find(FORGE_EVENT, loader);
            if (forgeEvent != null) {
                Object bus = forgeEvent.getField("BUS").get(null);
                Class.forName(FORGE_BUS, false, loader).getMethod("addListener", Consumer.class).invoke(bus, replay);
            } else {
                Object bus = Class.forName(NEOFORGE, false, loader).getField("EVENT_BUS").get(null);
                Class<?> event = Class.forName(NEOFORGE_EVENT, false, loader);
                Class.forName(NEOFORGE_BUS, false, loader).getMethod("addListener", Class.class, Consumer.class)
                        .invoke(bus, event, replay);
            }
            listening = true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod found no brewing event to add Forge brewing recipes to"
                    + " (looked for Forge's BrewingRecipeRegisterEvent.BUS and NeoForge's RegisterBrewingRecipesEvent)", e);
        }
    }

    /** Adds every kept recipe in the order the mod added them, since brewing uses the first recipe that matches. */
    static void replay(Object event) {
        List<Object[]> recipes;
        synchronized (LegacyBrewingRecipes.class) {
            recipes = new ArrayList<>(RECIPES);
        }
        try {
            // NeoForge's builder takes the recipes. Forge's builder has no addRecipe, so its event takes them.
            Object builder = LegacyPublicMethods.method(event.getClass(), "getBuilder").invoke(event);
            for (Object[] recipe : recipes) {
                Method add = addRecipeMethod(builder.getClass(), recipe.length);
                Object receiver = builder;
                if (add == null) {
                    add = addRecipeMethod(event.getClass(), recipe.length);
                    receiver = event;
                }
                if (add == null) {
                    throw new NoSuchMethodException(event.getClass().getName() + ".addRecipe with "
                            + recipe.length + " parameters");
                }
                add.invoke(receiver, recipe);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot add Forge brewing recipes to the brewing event", e);
        }
    }

    private static Method addRecipeMethod(Class<?> type, int parameters) {
        for (Method method : LegacyPublicMethods.methods(type)) {
            if (method.getName().equals("addRecipe") && method.getParameterCount() == parameters) return method;
        }
        return null;
    }

    /** {@code getRecipes()}: the running game's recipes, Minecraft's own first, then every mod's in order. */
    public static List<?> getRecipes() {
        Object brewing = currentBrewing();
        return brewing == null ? List.of() : (List<?>) call(brewing, "getRecipes");
    }

    /** {@code isValidInput(stack)}: some recipe takes this stack in a bottle slot. */
    public static boolean isValidInput(Object stack) {
        Object brewing = currentBrewing();
        return brewing != null && (Boolean) call(brewing, "isValidInput", stack);
    }

    /** {@code isValidIngredient(stack)}: some recipe takes this stack as its ingredient. */
    public static boolean isValidIngredient(Object stack) {
        Object brewing = currentBrewing();
        return brewing != null && (Boolean) call(brewing, "isIngredient", stack);
    }

    /** {@code getOutput(input, ingredient)}, or an empty stack when no recipe brews them. */
    public static Object getOutput(Object input, Object ingredient) {
        Object brewing = currentBrewing();
        if (brewing == null) return emptyStack();
        // The host's mix takes the ingredient first and the bottle second, the reverse of the old order.
        return call(brewing, "mix", ingredient, input);
    }

    /** {@code hasOutput(input, ingredient)}. */
    public static boolean hasOutput(Object input, Object ingredient) {
        return !(Boolean) call(getOutput(input, ingredient), "isEmpty");
    }

    /** The running server's brewing, else the client connection's, or null before either exists. */
    static Object currentBrewing() {
        ClassLoader loader = LegacyBrewingRecipes.class.getClassLoader();
        Class<?> hooks = find(FORGE_SERVER_HOOKS, loader);
        Object server = hooks == null ? null : callStatic(hooks, "getCurrentServer");
        if (server != null) return call(server, "potionBrewing");
        // A dedicated server has no client classes.
        Class<?> minecraft = find(MINECRAFT, loader);
        Object client = minecraft == null ? null : callStatic(minecraft, "getInstance");
        Object connection = client == null ? null : call(client, "getConnection");
        return connection == null ? null : call(connection, "potionBrewing");
    }

    private static Object emptyStack() {
        try {
            return Class.forName(STACK, true, LegacyBrewingRecipes.class.getClassLoader()).getField("EMPTY").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read ItemStack.EMPTY for an old brewing lookup", e);
        }
    }

    private static Object call(Object target, String name, Object... args) {
        return invoke(target.getClass(), target, name, args);
    }

    private static Object callStatic(Class<?> type, String name) {
        return invoke(type, null, name);
    }

    /** Calls a public method by name and argument count, keeping the lookup, and lets the method's own errors through. */
    private static Object invoke(Class<?> type, Object target, String name, Object... args) {
        Map<String, Method> methods = METHODS.computeIfAbsent(type, key -> new ConcurrentHashMap<>());
        Method method = methods.computeIfAbsent(name + '/' + args.length, key -> {
            for (Method candidate : LegacyPublicMethods.methods(type)) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == args.length) return candidate;
            }
            return null;
        });
        if (method == null) {
            throw new IllegalStateException("Cannot find " + type.getName() + "." + name + " for an old brewing lookup");
        }
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot call " + type.getName() + "." + name + " for an old brewing lookup", e);
        }
    }

    private static Class<?> find(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    /** Recipes added so far, for tests. */
    static synchronized int pendingCount() {
        return RECIPES.size();
    }
}
