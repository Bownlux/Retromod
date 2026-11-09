/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.ref.WeakReference;
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

/** Runs a mod's pre-1.20.5 Gson loot serializer as a codec, and hands old loot code the condition and function lists 26.3 holds as one holder. */
public final class LegacyLootSerializers {

    // Assembled at runtime: Retromod's release jar relocates its own Gson, and a plain literal
    // would be relocated with it instead of naming the Gson the game and the mod share.
    private static final String GSON = String.join(".", "com", "google", "gson") + ".";
    private static final String SERIALIZATION = "com.mojang.serialization.";
    private static final String LOOT = "net.minecraft.world.level.storage.loot.";
    private static final String CONDITION = LOOT + "predicates.LootItemCondition";
    private static final String FUNCTION = LOOT + "functions.LootItemFunction";
    private static final String HOLDER = "net.minecraft.core.Holder";

    // Each element handed out from a holder named by id, beside that holder, so handing the element back keeps
    // it named by id. Compared by identity, never by the element's own equals or hashCode, and held weakly:
    // the entry or function that holds the holder keeps both alive.
    private static final List<WeakReference<?>[]> NAMED = new ArrayList<>();

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
            Method get = LegacyPublicMethods.method(json.getClass(), "get", String.class);
            Method deserialize = LegacyPublicMethods.method(context.getClass(), "deserialize",
                    Class.forName(GSON + "JsonElement", false, loader), Type.class);
            Object listed = get.invoke(json, "conditions");
            if (listed != null) return deserialize.invoke(context, listed, condition.arrayType());
            // 26.3 data holds one "condition", which Retromod's data migration made from the list.
            Object single = get.invoke(json, "condition");
            Object conditions = Array.newInstance(condition, single == null ? 0 : 1);
            if (single != null) Array.set(conditions, 0, deserialize.invoke(context, single, condition));
            return conditions;
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read an old loot function's conditions", e);
        }
    }

    /** {@code super(conditions)} with a list, as a function built for 1.20.5 to 26.2 passes it, on 26.3. */
    public static Optional<Object> conditionHolder(List<?> conditions) {
        return conditionHolder(conditions == null ? null : conditions.toArray());
    }

    /** {@code super(conditions)} on 26.3, which takes the conditions as one optional holder. */
    public static Optional<Object> conditionHolder(Object[] conditions) {
        // Several conditions all had to pass, which is how the host's own builders combine them.
        return holderOf(conditions, LOOT + "predicates.ConditionUserBuilder", "buildCondition");
    }

    /** The functions a loot entry built before 26.3 passes, as 26.3's one optional modifier holder. */
    public static Optional<Object> functionHolder(List<?> functions) {
        // Several functions ran in order, which is how the host's own builders combine them.
        return holderOf(functions == null ? null : functions.toArray(), LOOT + "functions.FunctionUserBuilder", "buildFunction");
    }

    /** The list a function built before 26.3 takes, for 26.3's one optional condition holder. */
    public static List<Object> conditionList(Object condition) {
        return listOf(condition, CONDITION);
    }

    /** The function list a loot entry built before 26.3 takes, for 26.3's one optional modifier holder. */
    public static List<Object> functionList(Object modifier) {
        return listOf(modifier, FUNCTION);
    }

    /** The {@code conditions} field code built before 26.3 read off a loot entry, from the one condition 26.3 holds. */
    public static List<Object> entryConditions(Object entry) {
        return conditionList(containerField(entry, "condition"));
    }

    /** The {@code functions} field code built before 26.3 read off a single entry, from the one modifier 26.3 holds. */
    public static List<Object> entryFunctions(Object entry) {
        return functionList(containerField(entry, "modifier"));
    }

    /** A field 26.3's {@code LootPoolEntryContainer} declares, read past any field of the same name an entry declares. */
    private static Object containerField(Object entry, String name) {
        for (Class<?> type = entry.getClass(); type != null; type = type.getSuperclass()) {
            if (!type.getName().equals(LOOT + "entries.LootPoolEntryContainer")) continue;
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(entry);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retromod could not read " + name + " from the loot entry "
                        + entry.getClass().getName() + " on this Minecraft version", e);
            }
        }
        throw new IllegalStateException(entry.getClass().getName() + " is not a loot pool entry, so it has no " + name);
    }

    /** {@link #conditionList} as a function, which maps the condition field of a codec built before 26.3. */
    public static Function<Object, Object> conditionListOf() {
        return LegacyLootSerializers::conditionList;
    }

    /** A constructor that takes the condition list, given 26.3's one optional condition holder instead. */
    public static Function<Object, Object> takingConditionList(Function<Object, Object> constructor) {
        return condition -> constructor.apply(conditionList(condition));
    }

    private static List<Object> listOf(Object held, String type) {
        if (!(held instanceof Optional<?> optional) || optional.isEmpty()) return List.of();
        Object holder = optional.get();
        try {
            return List.of(element(holder, Class.forName(type, false, holder.getClass().getClassLoader())));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the " + type.substring(type.lastIndexOf('.') + 1)
                    + " held by " + holder + " for a mod built before 26.3", e);
        }
    }

    /** The holder's value, or a stand-in that reads it when used; one named by id is remembered with its holder. */
    private static Object element(Object holder, Class<?> type) throws ReflectiveOperationException {
        Method value = LegacyPublicMethods.method(holder.getClass(), "value");
        if (Boolean.TRUE.equals(LegacyPublicMethods.method(holder.getClass(), "isBound").invoke(holder))) {
            Object element = value.invoke(holder);
            if (namedById(holder)) remember(element, holder);
            return element;
        }
        // A predicate named by id is bound only once its registry loads, so it is read when used.
        Object element = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> invokeOn(proxy, method, holder, value, args));
        remember(element, holder);
        return element;
    }

    /** Whether the holder names a registry entry, as {@code unwrapKey()} says; a direct holder names none. */
    private static boolean namedById(Object holder) throws ReflectiveOperationException {
        Method key;
        try {
            key = LegacyPublicMethods.method(holder.getClass(), "unwrapKey");
        } catch (NoSuchMethodException noKeys) {
            return false;
        }
        return key.invoke(holder) instanceof Optional<?> present && present.isPresent();
    }

    private static void remember(Object element, Object holder) {
        synchronized (NAMED) {
            NAMED.removeIf(pair -> pair[0].get() == null || pair[1].get() == null);
            // Each read of the same named predicate hands out the same element, which is remembered once.
            for (WeakReference<?>[] pair : NAMED) {
                if (pair[0].get() == element && pair[1].get() == holder) return;
            }
            NAMED.add(new WeakReference<?>[] {new WeakReference<>(element), new WeakReference<>(holder)});
        }
    }

    /** The holder {@code element} was handed out from, or null when it was made some other way. */
    private static Object heldBy(Object element) {
        synchronized (NAMED) {
            for (WeakReference<?>[] pair : NAMED) {
                if (pair[0].get() == element) return pair[1].get();
            }
        }
        return null;
    }

    /** The one optional holder for {@code elements}: the holder each came from, combined by the host's builder method. */
    @SuppressWarnings("unchecked")
    private static Optional<Object> holderOf(Object[] elements, String builder, String combine) {
        if (elements == null || elements.length == 0) return Optional.empty();
        try {
            // A list's array names no Minecraft type, so this class's loader, the mod's, finds Holder then.
            ClassLoader component = elements.getClass().getComponentType().getClassLoader();
            ClassLoader loader = component != null ? component : LegacyLootSerializers.class.getClassLoader();
            Method direct = Class.forName(HOLDER, false, loader).getMethod("direct", Object.class);
            List<Object> holders = new ArrayList<>();
            for (Object element : elements) {
                Object held = heldBy(element);
                holders.add(held != null ? held : direct.invoke(null, element));
            }
            if (holders.size() == 1) return Optional.of(holders.get(0));
            return (Optional<Object>) Class.forName(builder, false, loader).getMethod(combine, List.class).invoke(null, holders);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not pass an old loot condition or function list", e);
        }
    }

    private static Object invokeOn(Object proxy, Method method, Object holder, Method value, Object[] args) throws Throwable {
        // The stand-in answers its own identity, so hashing or comparing it never needs the predicate bound.
        if (method.getDeclaringClass() == Object.class && !method.getName().equals("toString")) {
            return method.getName().equals("hashCode") ? System.identityHashCode(proxy) : proxy == args[0];
        }
        try {
            return method.invoke(value.invoke(holder), args);
        } catch (InvocationTargetException e) {
            // The host's own error, such as the one for a value that is not bound yet.
            throw e.getCause();
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
            Method getOps = dynamic.getMethod("getOps");
            Constructor<?> newDynamic = dynamic.getConstructor(ops, Object.class);
            Method deserialize = serializerMethod(serializer, "deserialize", 2);
            Method serialize = serializerMethod(serializer, "serialize", 3);

            Function<Object, Object> decode = input -> {
                try {
                    Object json = value.invoke(convert.invoke(input, jsonOps));
                    if (!jsonObject.isInstance(json)) {
                        return error.invoke(null, (Supplier<String>) () -> "expected an object, not " + json);
                    }
                    Object readOps = withRegistries(loader, getOps.invoke(input), jsonOps);
                    return success.invoke(null, deserialize.invoke(serializer, json, deserializationContext(loader, readOps)));
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

    /** JSON ops that still see the registries the loot is read with, so a nested value can name an entry by id. */
    private static Object withRegistries(ClassLoader loader, Object ops, Object jsonOps) throws ReflectiveOperationException {
        Class<?> registryOps;
        try {
            registryOps = Class.forName("net.minecraft.resources.RegistryOps", false, loader);
        } catch (ClassNotFoundException noRegistries) {
            return jsonOps;
        }
        if (!registryOps.isInstance(ops)) return jsonOps;
        return registryOps.getMethod("withParent", Class.forName(SERIALIZATION + "DynamicOps", true, loader)).invoke(ops, jsonOps);
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
        for (Method method : LegacyPublicMethods.methods(serializer.getClass())) {
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
        // A string names a registry entry, which only the holder codec reads; the entry's value is handed on.
        boolean named = Boolean.TRUE.equals(element.getMethod("isJsonPrimitive").invoke(json))
                && Boolean.TRUE.equals(Class.forName(GSON + "JsonPrimitive", true, loader).getMethod("isString").invoke(json));
        Object holders = named ? codecOf(target, "CODEC") : null;
        Object codec = holders != null ? holders : codecOf(target, "DIRECT_CODEC", "CODEC");
        if (codec != null) {
            // Through the public interface: a codec's own class is usually not public.
            Method parse = Class.forName(SERIALIZATION + "Decoder", true, loader)
                    .getMethod("parse", Class.forName(SERIALIZATION + "DynamicOps", true, loader), Object.class);
            Object value = result(loader, parse.invoke(codec, jsonOps, json), target);
            boolean holder = holders != null && Class.forName(HOLDER, false, loader).isInstance(value);
            return holder ? element(value, target) : value;
        }
        if (target.isEnum()) {
            String text = String.valueOf(element.getMethod("getAsString").invoke(json));
            for (Object constant : target.getEnumConstants()) {
                if (text.equalsIgnoreCase(serializedName(constant))) return constant;
            }
            return null;
        }
        Object gson = Class.forName(GSON + "Gson", true, loader).getConstructor().newInstance();
        return LegacyPublicMethods.method(gson.getClass(), "fromJson", element, Type.class).invoke(gson, json, type);
    }

    private static Object write(ClassLoader loader, Object jsonOps, Object value) throws ReflectiveOperationException {
        Class<?> element = Class.forName(GSON + "JsonElement", true, loader);
        if (value == null) return Class.forName(GSON + "JsonNull", true, loader).getField("INSTANCE").get(null);
        Object codec = codecOf(value.getClass(), "DIRECT_CODEC", "CODEC");
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
        return LegacyPublicMethods.method(gson.getClass(), "toJsonTree", Object.class).invoke(gson, value);
    }

    private static Object result(ClassLoader loader, Object dataResult, Class<?> target) throws ReflectiveOperationException {
        Class<?> type = Class.forName(SERIALIZATION + "DataResult", true, loader);
        Optional<?> value = (Optional<?>) type.getMethod("result").invoke(dataResult);
        if (value.isPresent()) return value.get();
        Object message = type.getMethod("error").invoke(dataResult);
        throw new IllegalArgumentException("could not read " + target.getSimpleName() + ": " + message);
    }

    /** The type's first static codec field of {@code names}; the direct one goes first where {@code CODEC} can hold holders. */
    private static Object codecOf(Class<?> type, String... names) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (String name : names) {
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
            return String.valueOf(LegacyPublicMethods.method(constant.getClass(), "getSerializedName").invoke(constant));
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
