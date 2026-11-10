/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.retromod.shim.forge.IngredientFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** An old mod's ingredient serializers registered, read and written through the rebuilt classes on Forge 26.2. */
class LegacyIngredientSerializersRuntimeTest {

    private static final String PREFIX = "com/retromod/embedded/oldmod/";
    private static final List<String> SLOTS = List.of("retromod.shared.legacy-ingredient-serializers.ids.v1",
            "retromod.shared.legacy-ingredient-serializers.closed.v1");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    private EventBus7Host host;
    private Object group;
    private ClassLoader mod;

    @BeforeEach
    void forge262() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        SLOTS.forEach(System.getProperties()::remove);
        host = new EventBus7Host();
        IngredientFixtures.defineHost(host);
        group = host.modGroup("oldmod");
        // The mod constructing now, as FML sets it.
        Object container = host.type(MOD_CONTAINER).getConstructor(String.class, host.type(EventBus7Host.BUS_GROUP))
                .newInstance("oldmod", group);
        host.type(MOD_LOADING_CONTEXT).getField("active").set(null, container);
        mod = IngredientFixtures.mod(host, transformer, PREFIX, IngredientFixtures.oldMod());
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
        SLOTS.forEach(System.getProperties()::remove);
    }

    @Test
    @DisplayName("Serializers registered before the registry event wait for it and are registered in order")
    void registeredInOrderAtTheEvent() throws Exception {
        Class<?> ingredient = modClass(ITEM_INGREDIENT);
        ingredient.getMethod("registerAll").invoke(null);
        Object first = ingredient.getField("FIRST").get(null);
        Object second = ingredient.getField("SECOND").get(null);
        assertEquals("oldmod:first", idOf(first), "getID answers before Forge registers anything");

        Object recipes = post("RECIPE_SERIALIZERS");
        assertTrue(registered(recipes).isEmpty(), "another registry's event registers nothing");
        Object ingredients = post("INGREDIENT_SERIALIZERS");
        List<Object[]> entries = registered(ingredients);
        assertEquals(List.of("oldmod:first", "oldmod:second"), entries.stream().map(e -> e[1].toString()).toList());
        assertSame(first, entries.get(0)[2]);
        assertSame(second, entries.get(1)[2]);
        assertSame(key("INGREDIENT_SERIALIZERS"), entries.get(0)[0]);
    }

    @Test
    @DisplayName("A serializer registered after the event stays out of the registry without throwing and keeps its id")
    void lateRegistrationIsSkipped() throws Exception {
        Class<?> ingredient = modClass(ITEM_INGREDIENT);
        ingredient.getMethod("registerAll").invoke(null);
        post("INGREDIENT_SERIALIZERS");

        ingredient.getMethod("registerLate").invoke(null);
        assertTrue(registered(post("INGREDIENT_SERIALIZERS")).isEmpty());
        assertEquals("oldmod:late", idOf(ingredient.getField("LATE").get(null)));
    }

    @Test
    @DisplayName("Registering the same id twice throws, as Forge 1.20.1 did")
    void duplicateIdThrows() throws Exception {
        Class<?> ingredient = modClass(ITEM_INGREDIENT);
        ingredient.getMethod("registerAll").invoke(null);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> ingredient.getMethod("registerAll").invoke(null));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test
    @DisplayName("The codec reads through the old JSON read with the whole object, and the ingredient has serializer()")
    void codecDecodesThroughTheOldRead() throws Exception {
        Object serializer = modClass(ITEM_INGREDIENT).getField("FIRST").get(null);
        JsonObject json = new JsonObject();
        json.addProperty("type", "oldmod:first");
        json.addProperty("item", "minecraft:stick");

        Object result = decode(serializer, json);
        String message = message(result);
        Object ingredient = value(result).orElseThrow(() -> new AssertionError(message));
        assertEquals(ITEM_INGREDIENT.replace('/', '.'), ingredient.getClass().getName());
        assertEquals("minecraft:stick", ingredient.getClass().getField("item").get(ingredient));
        // Forge asks the ingredient for its serializer when it writes it, through the host's abstract method.
        assertSame(serializer, host.type(LegacyIngredientSerializerBridge.HOST_ABSTRACT).getMethod("serializer")
                .invoke(ingredient));
    }

    @Test
    @DisplayName("The codec writes the old toJson's fields and leaves the type to Forge's dispatch")
    void codecEncodesThroughTheOldToJson() throws Exception {
        Class<?> type = modClass(ITEM_INGREDIENT);
        type.getMethod("registerAll").invoke(null);
        Object serializer = type.getField("FIRST").get(null);
        Object ingredient = type.getConstructor(String.class, modClass(ITEM_SERIALIZER))
                .newInstance("minecraft:stick", serializer);

        Map<String, Object> fields = new LinkedHashMap<>();
        Class<?>[] builderType = {host.type(RECORD_BUILDER)};
        Object builder = Proxy.newProxyInstance(host, builderType, (proxy, method, args) -> {
            if (method.getName().equals("add")) fields.put((String) args[0], args[1]);
            return proxy;
        });
        Object codec = codec(serializer);
        host.type(MAP_CODEC).getMethod("encode", Object.class, host.type(DYNAMIC_OPS), host.type(RECORD_BUILDER))
                .invoke(codec, ingredient, jsonOps(), builder);
        assertEquals(Map.of("item", new JsonPrimitive("minecraft:stick")), fields);
    }

    @Test
    @DisplayName("A read that throws, even a linkage error, becomes an error result instead of stopping the data load")
    void failingReadIsAnError() throws Exception {
        Object broken = modClass(BROKEN_SERIALIZER).getConstructor().newInstance();
        Object result = decode(broken, new JsonObject());
        assertTrue(value(result).isEmpty());
        assertTrue(message(result).contains("Ingredient.ItemValue"), message(result));
    }

    @Test
    @DisplayName("Forge 26.2's network read and write reach the old ones")
    void networkReachesTheOldMethods() throws Exception {
        Object serializer = modClass(ITEM_INGREDIENT).getField("FIRST").get(null);
        Class<?> buffers = host.type("net/minecraft/network/RegistryFriendlyByteBuf");
        Object buffer = buffers.getConstructor().newInstance();
        Class<?> rebuilt = modClass(PREFIX + LegacyIngredientSerializerBridge.SERIALIZER);
        Object read = rebuilt.getMethod("read", buffers).invoke(serializer, buffer);
        assertEquals("from the network", read.getClass().getField("item").get(read));
        rebuilt.getMethod("write", buffers, host.type(INGREDIENT)).invoke(serializer, buffer, read);
        assertEquals(List.of(read), serializer.getClass().getField("written").get(serializer));
    }

    // ---- helpers ----

    private Class<?> modClass(String name) throws ClassNotFoundException {
        return mod.loadClass(name.replace('/', '.'));
    }

    private Object idOf(Object serializer) throws Exception {
        Class<?> ingredient = modClass(ITEM_INGREDIENT);
        return String.valueOf(ingredient.getMethod("idOf", serializer.getClass()).invoke(null, serializer));
    }

    private Object key(String name) throws Exception {
        return host.type(KEYS).getField(name).get(null);
    }

    /** Posts Forge's RegisterEvent for one registry to the mod's bus, as Forge posts it to every mod. */
    private Object post(String keyName) throws Exception {
        Class<?> eventType = host.type(REGISTER_EVENT);
        Object event = eventType.getConstructor(host.type("net/minecraft/resources/ResourceKey"))
                .newInstance(key(keyName));
        Object bus = eventType.getMethod("getBus", host.type(EventBus7Host.BUS_GROUP)).invoke(null, group);
        host.type(EventBus7Host.EVENT_BUS).getMethod("post", host.type("net/minecraftforge/eventbus/internal/Event"))
                .invoke(bus, event);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> registered(Object event) throws Exception {
        return (List<Object[]>) event.getClass().getField("registered").get(event);
    }

    private Object codec(Object serializer) throws Exception {
        return host.type(LegacyIngredientSerializerBridge.HOST_SERIALIZER).getMethod("codec").invoke(serializer);
    }

    private Object jsonOps() throws Exception {
        return host.type(JSON_OPS).getField("INSTANCE").get(null);
    }

    /** {@code codec().decode(JsonOps.INSTANCE, map)} for the object's entries, as Forge's dispatch calls it. */
    private Object decode(Object serializer, JsonObject json) throws Exception {
        Class<?> pairs = host.type(PAIR);
        List<Object> entries = new ArrayList<>();
        for (Map.Entry<String, com.google.gson.JsonElement> field : json.entrySet()) {
            entries.add(pairs.getConstructor(Object.class, Object.class).newInstance(new JsonPrimitive(field.getKey()),
                    field.getValue()));
        }
        Object map = Proxy.newProxyInstance(host, new Class<?>[]{host.type(MAP_LIKE)},
                (proxy, method, args) -> method.getName().equals("entries") ? entries.stream() : null);
        return host.type(MAP_CODEC).getMethod("decode", host.type(DYNAMIC_OPS), host.type(MAP_LIKE))
                .invoke(codec(serializer), jsonOps(), map);
    }

    private static Optional<?> value(Object result) throws Exception {
        return (Optional<?>) result.getClass().getMethod("result").invoke(result);
    }

    private static String message(Object result) throws Exception {
        return String.valueOf(result.getClass().getMethod("message").invoke(result));
    }
}
