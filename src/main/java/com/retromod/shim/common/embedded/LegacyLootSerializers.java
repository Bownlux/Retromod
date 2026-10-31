/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Runs a mod's pre-1.20.5 Gson loot serializer as the codec that loot types take from 1.20.5. */
public final class LegacyLootSerializers {

    // Assembled at runtime: Retromod's release jar relocates its own Gson, and a plain literal
    // would be relocated with it instead of naming the Gson the game and the mod share.
    private static final String GSON = String.join(".", "com", "google", "gson") + ".";
    private static final String SERIALIZATION = "com.mojang.serialization.";
    private static final String CONDITION = "net.minecraft.world.level.storage.loot.predicates.LootItemCondition";

    private LegacyLootSerializers() {}

    /** The codec for {@code new LootItemFunctionType(serializer)}. */
    public static Object functionCodec(Object serializer) {
        return codec(serializer);
    }

    /** The codec for {@code new LootItemConditionType(serializer)}. */
    public static Object conditionCodec(Object serializer) {
        return codec(serializer);
    }

    /** The {@code LootItemCondition[]} an old conditional function's JSON lists under {@code conditions}. */
    public static Object conditions(Object json, Object context) {
        try {
            // This class's loader, which is the mod's: Gson's own may not see Minecraft.
            ClassLoader loader = LegacyLootSerializers.class.getClassLoader();
            Class<?> condition = Class.forName(CONDITION, false, loader);
            Object listed = json.getClass().getMethod("get", String.class).invoke(json, "conditions");
            if (listed == null) return Array.newInstance(condition, 0);
            Method deserialize = context.getClass().getMethod("deserialize",
                    Class.forName(GSON + "JsonElement", false, loader), Type.class);
            return deserialize.invoke(context, listed, condition.arrayType());
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read an old loot function's conditions", e);
        }
    }

    /** {@code super(conditions)} on 26.3, which takes the conditions as one optional holder. */
    public static Optional<Object> conditionHolder(Object[] conditions) {
        if (conditions == null || conditions.length == 0) return Optional.empty();
        try {
            ClassLoader loader = conditions.getClass().getComponentType().getClassLoader();
            Class<?> holder = Class.forName("net.minecraft.core.Holder", false, loader);
            Method direct = holder.getMethod("direct", Object.class);
            if (conditions.length == 1) return Optional.of(direct.invoke(null, conditions[0]));
            // Several conditions all had to pass, which is what an all_of condition does.
            List<Object> holders = new ArrayList<>();
            for (Object condition : conditions) holders.add(direct.invoke(null, condition));
            Class<?> holderSet = Class.forName("net.minecraft.core.HolderSet", false, loader);
            Object set = holderSet.getMethod("direct", List.class).invoke(null, holders);
            Object allOf = Class.forName("net.minecraft.world.level.storage.loot.predicates.AllOfCondition", false, loader)
                    .getMethod("allOf", holderSet).invoke(null, set);
            return Optional.of(direct.invoke(null, allOf));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not pass an old loot function's conditions", e);
        }
    }

    // ---- the codec ---------------------------------------------------------------------------

    /** {@code MapCodec.assumeMapUnsafe(Codec.PASSTHROUGH.flatXmap(decode, encode))} over the serializer. */
    private static Object codec(Object serializer) {
        try {
            ClassLoader loader = serializer.getClass().getClassLoader();
            Class<?> codec = Class.forName(SERIALIZATION + "Codec", true, loader);
            Class<?> dataResult = Class.forName(SERIALIZATION + "DataResult", true, loader);
            Class<?> dynamic = Class.forName(SERIALIZATION + "Dynamic", true, loader);
            Class<?> ops = Class.forName(SERIALIZATION + "DynamicOps", true, loader);
            Object jsonOps = Class.forName(SERIALIZATION + "JsonOps", true, loader).getField("INSTANCE").get(null);
            Class<?> jsonObject = Class.forName(GSON + "JsonObject", true, loader);
            Method success = dataResult.getMethod("success", Object.class);
            Method error = dataResult.getMethod("error", Supplier.class);
            Method convert = dynamic.getMethod("convert", ops);
            Method value = dynamic.getMethod("getValue");
            Constructor<?> newDynamic = dynamic.getConstructor(ops, Object.class);
            Method deserialize = serializerMethod(serializer, "deserialize", 2);
            Method serialize = serializerMethod(serializer, "serialize", 3);

            Function<Object, Object> decode = input -> {
                try {
                    Object json = value.invoke(convert.invoke(input, jsonOps));
                    if (!jsonObject.isInstance(json)) {
                        return error.invoke(null, (Supplier<String>) () -> "expected an object, not " + json);
                    }
                    return success.invoke(null, deserialize.invoke(serializer, json, deserializationContext(loader, jsonOps)));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Throwable cause = e instanceof InvocationTargetException wrapped ? wrapped.getCause() : e;
                    return failure(error, serializer, cause);
                }
            };
            Function<Object, Object> encode = loot -> {
                try {
                    Object json = jsonObject.getConstructor().newInstance();
                    serialize.invoke(serializer, json, loot, serializationContext(loader, jsonOps));
                    return success.invoke(null, newDynamic.newInstance(jsonOps, json));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Throwable cause = e instanceof InvocationTargetException wrapped ? wrapped.getCause() : e;
                    return failure(error, serializer, cause);
                }
            };
            Object mapped = codec.getMethod("flatXmap", Function.class, Function.class)
                    .invoke(codec.getField("PASSTHROUGH").get(null), decode, encode);
            return Class.forName(SERIALIZATION + "MapCodec", true, loader).getMethod("assumeMapUnsafe", codec)
                    .invoke(null, mapped);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not adapt the old loot serializer "
                    + serializer.getClass().getName(), e);
        }
    }

    private static Object failure(Method error, Object serializer, Throwable cause) {
        try {
            return error.invoke(null, (Supplier<String>) () -> serializer.getClass().getName() + ": " + cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The serializer's erased {@code deserialize(JsonObject, context)} or {@code serialize(JsonObject, value, context)}. */
    private static Method serializerMethod(Object serializer, String name, int arity) throws NoSuchMethodException {
        for (Method method : serializer.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arity) continue;
            boolean erased = name.equals("deserialize") ? method.getReturnType() == Object.class
                    : method.getParameterTypes()[1] == Object.class;
            if (erased) return method;
        }
        throw new NoSuchMethodException(serializer.getClass().getName() + "." + name);
    }

    // ---- Gson contexts -----------------------------------------------------------------------

    // The Gson contexts an old serializer reads and writes nested values with, answered by codecs.
    // Plain methods and lambdas, not a nested class: this class is embedded into each mod alone.

    private static Object deserializationContext(ClassLoader loader, Object jsonOps) throws ClassNotFoundException {
        return proxy(loader, "JsonDeserializationContext", (proxy, method, args) -> switch (method.getName()) {
            case "deserialize" -> read(loader, jsonOps, args[0], (Type) args[1]);
            default -> objectMethod(proxy, method, args);
        });
    }

    private static Object serializationContext(ClassLoader loader, Object jsonOps) throws ClassNotFoundException {
        return proxy(loader, "JsonSerializationContext", (proxy, method, args) -> switch (method.getName()) {
            case "serialize" -> write(loader, jsonOps, args[0]);
            default -> objectMethod(proxy, method, args);
        });
    }

    private static Object proxy(ClassLoader loader, String type, InvocationHandler handler) throws ClassNotFoundException {
        Class<?> context = Class.forName(GSON + type, true, loader);
        return Proxy.newProxyInstance(context.getClassLoader(), new Class<?>[] {context}, handler);
    }

    /** A nested value: through its type's codec, by its serialized name for an enum, else by Gson. */
    private static Object read(ClassLoader loader, Object jsonOps, Object json, Type type) throws ReflectiveOperationException {
        Class<?> target = raw(type);
        Class<?> element = Class.forName(GSON + "JsonElement", true, loader);
        if (json == null || Boolean.TRUE.equals(element.getMethod("isJsonNull").invoke(json))) return null;
        if (target.isArray()) {
            Iterable<?> elements = (Iterable<?>) element.getMethod("getAsJsonArray").invoke(json);
            List<Object> values = new ArrayList<>();
            for (Object item : elements) values.add(read(loader, jsonOps, item, target.getComponentType()));
            Object array = Array.newInstance(target.getComponentType(), values.size());
            for (int i = 0; i < values.size(); i++) Array.set(array, i, values.get(i));
            return array;
        }
        Object codec = codecOf(target);
        if (codec != null) {
            // Through the public interface: a codec's own class is usually not public.
            Method parse = Class.forName(SERIALIZATION + "Decoder", true, loader)
                    .getMethod("parse", Class.forName(SERIALIZATION + "DynamicOps", true, loader), Object.class);
            return result(loader, parse.invoke(codec, jsonOps, json), target);
        }
        if (target.isEnum()) {
            String text = String.valueOf(element.getMethod("getAsString").invoke(json));
            for (Object constant : target.getEnumConstants()) {
                if (text.equalsIgnoreCase(serializedName(constant))) return constant;
            }
            return null;
        }
        Object gson = Class.forName(GSON + "Gson", true, loader).getConstructor().newInstance();
        return gson.getClass().getMethod("fromJson", element, Type.class).invoke(gson, json, type);
    }

    private static Object write(ClassLoader loader, Object jsonOps, Object value) throws ReflectiveOperationException {
        Class<?> element = Class.forName(GSON + "JsonElement", true, loader);
        if (value == null) return Class.forName(GSON + "JsonNull", true, loader).getField("INSTANCE").get(null);
        Object codec = codecOf(value.getClass());
        if (codec != null) {
            Method encode = Class.forName(SERIALIZATION + "Encoder", true, loader)
                    .getMethod("encodeStart", Class.forName(SERIALIZATION + "DynamicOps", true, loader), Object.class);
            return result(loader, encode.invoke(codec, jsonOps, value), element);
        }
        if (value instanceof Enum<?>) {
            return Class.forName(GSON + "JsonPrimitive", true, loader).getConstructor(String.class)
                    .newInstance(serializedName(value));
        }
        Object gson = Class.forName(GSON + "Gson", true, loader).getConstructor().newInstance();
        return gson.getClass().getMethod("toJsonTree", Object.class).invoke(gson, value);
    }

    private static Object result(ClassLoader loader, Object dataResult, Class<?> target) throws ReflectiveOperationException {
        Class<?> type = Class.forName(SERIALIZATION + "DataResult", true, loader);
        Optional<?> value = (Optional<?>) type.getMethod("result").invoke(dataResult);
        if (value.isPresent()) return value.get();
        Object message = type.getMethod("error").invoke(dataResult);
        throw new IllegalArgumentException("could not read " + target.getSimpleName() + ": " + message);
    }

    /** A static codec field of the type: the direct one first, because {@code CODEC} can hold holders. */
    private static Object codecOf(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (String name : new String[] {"DIRECT_CODEC", "CODEC"}) {
                try {
                    Field field = current.getField(name);
                    if (Modifier.isStatic(field.getModifiers()) && field.getType().getName().equals(SERIALIZATION + "Codec")) {
                        return field.get(null);
                    }
                } catch (ReflectiveOperationException | RuntimeException notHere) {
                    // the next name or supertype may have it
                }
            }
        }
        return null;
    }

    /** {@code getSerializedName()} for a vanilla enum, else its name. */
    private static String serializedName(Object constant) {
        try {
            return String.valueOf(constant.getClass().getMethod("getSerializedName").invoke(constant));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return ((Enum<?>) constant).name();
        }
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        return Object.class;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Retromod loot serializer context";
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException(cause);
    }
}
