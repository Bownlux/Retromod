/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.embedded;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

/**
 * Runtime half of {@link com.retromod.shim.fabric.Pre1_20_5LootDeserializersBridge}: a Gson
 * builder that reads and writes loot tables, pools, conditions and functions through the codecs
 * Minecraft 1.20.5 replaced the old Gson loot serializers with.
 *
 * <p>Each registered type gets a hierarchy adapter that decodes with the type's codec and
 * {@code JsonOps}. That covers loot JSON that names items, blocks and other built-in registry
 * entries. A reference into a data pack registry, such as an enchantment, needs registry access
 * the old API never passed, so that element fails to parse with the codec's own message.
 *
 * <p>Everything here is reflective. Retromod does not compile against DataFixerUpper, and its
 * release jar relocates its own Gson, so a direct Gson reference would name Retromod's private
 * copy instead of the one the game and the mod share. Library names are assembled at runtime for
 * the same reason. Every transformed mod gets its own relocated copy of this class.
 */
public final class LegacyLootGson implements InvocationHandler {

    private static final String GSON = String.join(".", "com", "google", "gson") + ".";
    private static final String JSON_OPS = "com.mojang.serialization.JsonOps";
    private static final String DYNAMIC_OPS = "com.mojang.serialization.DynamicOps";

    private static final String LOOT_TABLE = "net.minecraft.class_52";
    private static final String LOOT_POOL = "net.minecraft.class_55";
    private static final String CONDITION = "net.minecraft.class_5341";
    private static final String FUNCTION = "net.minecraft.class_117";
    private static final String FUNCTIONS = "net.minecraft.class_131";

    /** {@code LootTable.DIRECT_CODEC}, {@code LootPool.CODEC}, and the condition and function codecs. */
    private static final String[][] LOOT_TABLE_CODECS = {
        {LOOT_TABLE, LOOT_TABLE, "field_50021"},
        {LOOT_POOL, LOOT_POOL, "field_45795"},
        {CONDITION, CONDITION, "field_51809"},
        {FUNCTION, FUNCTIONS, "field_50023"},
    };
    private static final String[][] FUNCTION_CODECS = {LOOT_TABLE_CODECS[2], LOOT_TABLE_CODECS[3]};
    private static final String[][] CONDITION_CODECS = {LOOT_TABLE_CODECS[2]};

    private final Object codec;
    private final Object ops;
    private final Class<?> parseException;

    private LegacyLootGson(Object codec, Object ops, Class<?> parseException) {
        this.codec = codec;
        this.ops = ops;
        this.parseException = parseException;
    }

    /** {@code Deserializers.createLootTableSerializer()} in its pre-1.20.5 form. */
    public static Object lootTableBuilder() {
        return builder(LOOT_TABLE_CODECS);
    }

    /** {@code Deserializers.createFunctionSerializer()} in its pre-1.20.5 form. */
    public static Object functionBuilder() {
        return builder(FUNCTION_CODECS);
    }

    /** {@code Deserializers.createConditionSerializer()} in its pre-1.20.5 form. */
    public static Object conditionBuilder() {
        return builder(CONDITION_CODECS);
    }

    private static Object builder(String[][] codecs) {
        try {
            ClassLoader loader = LegacyLootGson.class.getClassLoader();
            Class<?> builderClass = Class.forName(GSON + "GsonBuilder", false, loader);
            Class<?> deserializer = Class.forName(GSON + "JsonDeserializer", false, loader);
            Class<?> serializer = Class.forName(GSON + "JsonSerializer", false, loader);
            Class<?> parseException = Class.forName(GSON + "JsonParseException", false, loader);
            Object ops = Class.forName(JSON_OPS, false, loader).getField("INSTANCE").get(null);
            Object builder = builderClass.getConstructor().newInstance();
            Method register = builderClass.getMethod("registerTypeHierarchyAdapter", Class.class, Object.class);
            for (String[] entry : codecs) {
                Class<?> type = Class.forName(entry[0], false, loader);
                Object codec = Class.forName(entry[1], true, loader).getField(entry[2]).get(null);
                Object adapter = Proxy.newProxyInstance(loader, new Class<?>[] {deserializer, serializer},
                        new LegacyLootGson(codec, ops, parseException));
                register.invoke(builder, type, adapter);
            }
            return builder;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build the pre-1.20.5 loot table Gson "
                    + "serializers on this Minecraft version: " + e, e);
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "deserialize":
                return unwrap(call(codec, "parse", ops, args[0]), "read");
            case "serialize":
                return unwrap(call(codec, "encodeStart", ops, args[0]), "write");
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return "Retromod loot codec adapter for " + codec;
            default:
                throw new UnsupportedOperationException(method.getName());
        }
    }

    /** The result of a {@code DataResult}, or a Gson parse exception carrying the codec's message. */
    private Object unwrap(Object dataResult, String action) throws Throwable {
        Optional<?> result = (Optional<?>) call(dataResult, "result");
        if (result.isPresent()) return result.get();
        Optional<?> error = (Optional<?>) call(dataResult, "error");
        String message = error.isPresent() ? String.valueOf(call(error.get(), "message")) : "no details";
        throw (Throwable) parseException.getConstructor(String.class)
                .newInstance("Could not " + action + " loot data: " + message);
    }

    private static Object call(Object target, String name, Object... args) throws Throwable {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length
                    && (args.length == 0 || isOps(method.getParameterTypes()[0]))) {
                // Codec implementations are often private classes; the method itself is public.
                method.trySetAccessible();
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static boolean isOps(Class<?> type) {
        return type.getName().equals(DYNAMIC_OPS);
    }
}
