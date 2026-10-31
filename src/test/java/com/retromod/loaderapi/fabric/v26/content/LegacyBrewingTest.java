/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.loaderapi.fabric.v26.net.FakeHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old brewing registrations become minecraft:brewing recipes that a fake 26.3 world loads on its next start. */
class LegacyBrewingTest {

    private static final String BREWING = "com.retromod.loaderapi.fabric.v26.content.LegacyBrewing";
    private static final String BUILDER = "com.retromod.loaderapi.fabric.v26.content.shared.LegacyPotionBrewingBuilder";
    private static final String CALLBACK = "com.retromod.loaderapi.fabric.v26.content.shared.LegacyBrewingBuildCallback";

    @Test
    @DisplayName("A BUILD callback's mixes become one recipe per bottle, written when the server starts")
    void buildCallbackMixesBecomeRecipes(@TempDir Path world) throws Throwable {
        try (FakeHost host = FakeHost.start()) {
            Object awkward = potion(host, "minecraft", "awkward");
            Object luck = potion(host, "somemod", "luck");
            Object clover = item(host, "somemod", "clover");
            // The mod's callback, as its lambda over the old BuildCallback becomes after translation.
            register(host, builder -> {
                call(host, BREWING, "addMix", builder, holder(host, awkward), clover, holder(host, luck));
                call(host, BREWING, "registerRecipes", builder, ingredient(host, clover), holder(host, luck));
            });
            assertEquals(1, serverStartedListeners(host), "reading BUILD starts the server start listener");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> recipes = (List<Map<String, Object>>) call(host, BREWING, "collect", server(host));
            // addMix in three bottles, then registerRecipes' water and awkward mixes in three each.
            assertEquals(9, recipes.size());

            Path pack = world.resolve("retromod-legacy-brewing");
            assertEquals(9, call(host, BREWING, "write", pack, recipes, 97));
            JsonObject splash = json(pack.resolve("data/retromod_legacy/recipe/legacy_brewing/1.json"));
            assertEquals("minecraft:brewing", splash.get("type").getAsString());
            assertEquals("minecraft:splash_potion", splash.getAsJsonObject("input").get("item").getAsString());
            assertEquals("minecraft:awkward", splash.getAsJsonObject("input")
                    .getAsJsonObject("potion_contents").get("potions").getAsString());
            assertEquals("somemod:clover", splash.getAsJsonObject("reagent").get("item").getAsString());
            assertEquals("somemod:luck", splash.getAsJsonObject("output").getAsJsonObject("components")
                    .getAsJsonObject("minecraft:potion_contents").get("potion").getAsString());
            JsonObject mundane = json(pack.resolve("data/retromod_legacy/recipe/legacy_brewing/3.json"));
            assertEquals("minecraft:water", mundane.getAsJsonObject("input")
                    .getAsJsonObject("potion_contents").get("potions").getAsString());
            assertTrue(Files.exists(pack.resolve("pack.mcmeta")));
        }
    }

    @Test
    @DisplayName("A container recipe moves every potion, and a 1.20.1 static recipe joins the same pack")
    void containerAndStaticRecipes() throws Throwable {
        try (FakeHost host = FakeHost.start()) {
            Object water = potion(host, "minecraft", "water");
            Object luck = potion(host, "somemod", "luck");
            Object bottle = item(host, "minecraft", "potion");
            Object jar = item(host, "somemod", "potion_jar");
            Object glass = item(host, "minecraft", "glass");
            Object clover = item(host, "somemod", "clover");
            Object sugar = item(host, "minecraft", "sugar");
            register(host, builder -> call(host, BREWING, "addContainerRecipe", builder, bottle, glass, jar));
            call(host, BREWING, "registerStaticPotionRecipe", water, ingredient(host, clover, sugar), luck);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> recipes = (List<Map<String, Object>>) call(host, BREWING, "collect", server(host));

            // The static mix in three bottles first, then the container mix once per potion.
            assertEquals(5, recipes.size());
            assertEquals(List.of("somemod:clover", "minecraft:sugar"),
                    ((Map<?, ?>) recipes.get(0).get("reagent")).get("item"), "an ingredient of two items lists both");
            Map<?, ?> moved = (Map<?, ?>) recipes.get(4).get("output");
            assertEquals("somemod:potion_jar", moved.get("id"));
            assertEquals("{minecraft:potion_contents={potion=somemod:luck}}", String.valueOf(moved.get("components")));
        }
    }

    @Test
    @DisplayName("On 26.1 and 26.2 the 1.20.1 static recipes join Fabric API's own builder")
    void staticRecipesJoinTheHostBuilder() throws Throwable {
        try (FakeHost host = FakeHost.start()) {
            Object water = potion(host, "minecraft", "water");
            Object luck = potion(host, "somemod", "luck");
            Object bottle = item(host, "minecraft", "potion");
            Object jar = item(host, "somemod", "potion_jar");
            call(host, BREWING, "registerStaticPotionRecipe", water, ingredient(host, bottle), luck);
            call(host, BREWING, "registerStaticItemRecipe", bottle, ingredient(host, bottle), jar);

            Object hostBuilder = host.type("net.minecraft.world.item.alchemy.PotionBrewing$Builder")
                    .getConstructor().newInstance();
            call(host, BREWING, "replay", hostBuilder);

            @SuppressWarnings("unchecked")
            List<String> events = (List<String>) host.type("fixture.Recorder").getField("EVENTS").get(null);
            assertEquals(List.of("potion recipe water -> luck", "item recipe potion -> potion_jar"), events,
                    "a 1.20.1 potion is wrapped in the holder the builder takes");
        }
    }

    // ---- Fake host access --------------------------------------------------------------------

    /** Registers {@code body} on the old BUILD event, as a translated mod's callback. */
    private static void register(FakeHost host, ThrowingConsumer body) throws Throwable {
        Class<?> callbackType = host.type(CALLBACK);
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, args) -> {
                    if (method.getName().equals("build")) body.accept(args[0]);
                    return null;
                });
        Object event = host.type(BUILDER).getField("BUILD").get(null);
        event.getClass().getMethod("register", Object.class).invoke(event, callback);
    }

    private interface ThrowingConsumer {
        void accept(Object value) throws Throwable;
    }

    private static int serverStartedListeners(FakeHost host) throws Exception {
        Object event = host.type("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents")
                .getField("SERVER_STARTED").get(null);
        return ((List<?>) event.getClass().getField("listeners").get(event)).size();
    }

    private static Object server(FakeHost host) throws Exception {
        return host.type("net.minecraft.server.MinecraftServer").getConstructor().newInstance();
    }

    private static Object item(FakeHost host, String namespace, String path) throws Throwable {
        Object item = host.type("net.minecraft.world.item.Item").getConstructor(String.class).newInstance(path);
        put(host, "ITEM", namespace + ":" + path, item);
        return item;
    }

    private static Object potion(FakeHost host, String namespace, String path) throws Throwable {
        Object potion = host.type("net.minecraft.world.item.alchemy.Potion").getConstructor(String.class).newInstance(path);
        put(host, "POTION", namespace + ":" + path, potion);
        return potion;
    }

    private static void put(FakeHost host, String registry, String id, Object value) throws Throwable {
        Object entries = host.type("net.minecraft.core.registries.BuiltInRegistries").getField(registry).get(null);
        String[] parts = id.split(":", 2);
        Object identifier = call(host, "net.minecraft.resources.Identifier", "fromNamespaceAndPath", parts[0], parts[1]);
        entries.getClass().getMethod("put", host.type("net.minecraft.resources.Identifier"), Object.class)
                .invoke(entries, identifier, value);
    }

    private static Object holder(FakeHost host, Object value) throws Throwable {
        return call(host, "net.minecraft.core.Holder", "direct", value);
    }

    private static Object ingredient(FakeHost host, Object... items) throws Throwable {
        Class<?> itemType = host.type("net.minecraft.world.item.Item");
        Object array = java.lang.reflect.Array.newInstance(itemType, items.length);
        for (int i = 0; i < items.length; i++) java.lang.reflect.Array.set(array, i, items[i]);
        return host.type("net.minecraft.world.item.crafting.Ingredient")
                .getMethod("of", array.getClass()).invoke(null, array);
    }

    private static JsonObject json(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    /** Calls a static method by name and argument count, package-private ones included. */
    private static Object call(FakeHost host, String className, String method, Object... args) throws Throwable {
        for (Method candidate : host.type(className).getDeclaredMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
                candidate.setAccessible(true);
                try {
                    return candidate.invoke(null, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new NoSuchMethodException(className + "." + method + " with " + args.length + " arguments");
    }
}
