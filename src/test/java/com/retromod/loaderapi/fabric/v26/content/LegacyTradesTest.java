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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old Fabric trade registrations become villager_trade data that a fake 26.x world loads on its next start. */
class LegacyTradesTest {

    private static final String TRADES = "com.retromod.loaderapi.fabric.v26.content.LegacyTrades";

    @Test
    @DisplayName("Villager and wandering trader offers join the tags their 26.x trade lists read")
    void offersBecomeVillagerTradeData(@TempDir Path world) throws Throwable {
        try (FakeHost host = FakeHost.start()) {
            Object wheat = item(host, "minecraft", "wheat");
            Object emerald = item(host, "minecraft", "emerald");
            Object ruby = item(host, "somemod", "ruby");
            Object farmer = profession(host, "farmer", "minecraft:farmer/level_1");
            Consumer<List<Object>> farmerOffers = listings -> listings.add(listing(host, offer(host, wheat, 20, emerald, 1)));
            Consumer<List<Object>> wandererOffers = listings -> listings.add(listing(host, offer(host, emerald, 5, ruby, 1)));

            call(host, TRADES, "registerVillagerOffers", farmer, 1, farmerOffers);
            call(host, TRADES, "registerWanderingTraderOffers", 2, wandererOffers);

            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> byTag = (Map<String, List<Map<String, Object>>>) call(host, TRADES, "collect");
            assertEquals(List.of("minecraft:farmer/level_1", "minecraft:wandering_trader/uncommon"), List.copyOf(byTag.keySet()));

            Path pack = world.resolve("retromod-legacy-trades");
            assertEquals(2, call(host, TRADES, "write", pack, byTag, 97));
            JsonObject trade = json(pack.resolve("data/retromod_legacy/villager_trade/legacy/minecraft/farmer/level_1/0.json"));
            assertEquals("minecraft:wheat", trade.getAsJsonObject("wants").get("id").getAsString());
            assertEquals(20, trade.getAsJsonObject("wants").get("count").getAsInt());
            assertEquals("minecraft:emerald", trade.getAsJsonObject("gives").get("id").getAsString());
            assertFalse(trade.has("additional_wants"), "an empty second price is left out");
            JsonObject tag = json(pack.resolve("data/minecraft/tags/villager_trade/farmer/level_1.json"));
            assertFalse(tag.get("replace").getAsBoolean(), "vanilla's trades stay in the tag");
            assertEquals("retromod_legacy:legacy/minecraft/farmer/level_1/0",
                    tag.getAsJsonArray("values").get(0).getAsJsonObject().get("id").getAsString());
            assertEquals(List.of("id", "required"), List.copyOf(tag.getAsJsonArray("values").get(0)
                    .getAsJsonObject().keySet()), "the same trades write the same file on every start");
            assertTrue(Files.exists(pack.resolve("pack.mcmeta")));
        }
    }

    @Test
    @DisplayName("A profession passed as its registry key reads the same trade list")
    void professionKeyReadsTheSameList() throws Throwable {
        try (FakeHost host = FakeHost.start()) {
            Object emerald = item(host, "minecraft", "emerald");
            Object bread = item(host, "minecraft", "bread");
            profession(host, "baker", "somemod:baker/level_2");
            Object key = professionKey(host, "baker");
            Consumer<List<Object>> offers = listings -> listings.add(listing(host, offer(host, emerald, 1, bread, 6)));

            call(host, TRADES, "registerVillagerOffers", key, 2, offers);

            assertEquals(List.of("somemod:baker/level_2"), List.copyOf(((Map<?, ?>) call(host, TRADES, "collect")).keySet()));
        }
    }

    // ---- Fake host access --------------------------------------------------------------------

    private static Object item(FakeHost host, String namespace, String path) throws Throwable {
        Object item = host.type("net.minecraft.world.item.Item").getConstructor(String.class).newInstance(path);
        Object items = host.type("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").get(null);
        items.getClass().getMethod("put", host.type("net.minecraft.resources.Identifier"), Object.class)
                .invoke(items, id(host, namespace + ":" + path), item);
        return item;
    }

    private static Object profession(FakeHost host, String name, String tradeSet) throws Throwable {
        Object setKey = call(host, "net.minecraft.resources.ResourceKey", "create",
                call(host, "net.minecraft.resources.ResourceKey", "createRegistryKey", id(host, "minecraft:trade_set")),
                id(host, tradeSet));
        int level = Integer.parseInt(tradeSet.substring(tradeSet.length() - 1));
        Object profession = host.type("net.minecraft.world.entity.npc.villager.VillagerProfession")
                .getConstructor(Map.class).newInstance(Map.of(level, setKey));
        Object professions = host.type("net.minecraft.core.registries.BuiltInRegistries").getField("VILLAGER_PROFESSION").get(null);
        professions.getClass().getMethod("put", host.type("net.minecraft.resources.Identifier"), Object.class)
                .invoke(professions, id(host, "minecraft:" + name), profession);
        return profession;
    }

    private static Object professionKey(FakeHost host, String name) throws Throwable {
        return call(host, "net.minecraft.resources.ResourceKey", "create",
                call(host, "net.minecraft.resources.ResourceKey", "createRegistryKey", id(host, "minecraft:villager_profession")),
                id(host, "minecraft:" + name));
    }

    private static Object offer(FakeHost host, Object wants, int wantsCount, Object gives, int givesCount) {
        try {
            Class<?> stack = host.type("net.minecraft.world.item.ItemStack");
            Class<?> item = host.type("net.minecraft.world.item.Item");
            Object price = stack.getConstructor(item, int.class).newInstance(wants, wantsCount);
            Object result = stack.getConstructor(item, int.class).newInstance(gives, givesCount);
            Object none = stack.getConstructor(item, int.class).newInstance(null, 0);
            return host.type("net.minecraft.world.item.trading.MerchantOffer")
                    .getConstructor(stack, stack, stack, int.class, int.class, float.class)
                    .newInstance(price, none, result, 16, 2, 0.05f);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A listing like a mod's lambda over the embedded trade factory interface. */
    private static Object listing(FakeHost host, Object offer) {
        try {
            Class<?> type = host.type("fixture.FakeItemListing");
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> method.getName().equals("getOffer") ? offer : null);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object id(FakeHost host, String text) throws Throwable {
        String[] parts = text.split(":", 2);
        return call(host, "net.minecraft.resources.Identifier", "fromNamespaceAndPath", parts[0], parts[1]);
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
