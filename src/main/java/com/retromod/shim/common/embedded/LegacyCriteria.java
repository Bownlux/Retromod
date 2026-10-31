/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import com.retromod.shim.forge.embedded.LateVanillaRegistration;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** Runs a mod's pre-1.20.2 JSON advancement trigger API on the codecs that replaced it. */
public final class LegacyCriteria {

    // Assembled at runtime so the release jar's Gson relocation leaves the game's Gson names alone.
    private static final String GSON = String.join(".", "com", "google", "gson") + ".";
    private static final String SERIALIZATION = "com.mojang.serialization.";
    // Each predicate class's package moved twice in 26.x, so each is found by its first match.
    private static final String[] CONTEXT_PREDICATE = {"net.minecraft.advancements.critereon.ContextAwarePredicate",
            "net.minecraft.advancements.criterion.ContextAwarePredicate",
            "net.minecraft.advancements.predicates.ContextAwarePredicate"};
    private static final String[] ENTITY_PREDICATE = {"net.minecraft.advancements.critereon.EntityPredicate",
            "net.minecraft.advancements.criterion.EntityPredicate",
            "net.minecraft.advancements.predicates.entity.EntityPredicate"};
    // 26.3 replaced the context predicate with a holder of one loot condition, read with its codec.
    private static final String[] LOOT_CONDITION = {"net.minecraft.world.level.storage.loot.predicates.LootItemCondition"};
    private static final String[] ITEM_PREDICATE = {"net.minecraft.advancements.critereon.ItemPredicate",
            "net.minecraft.advancements.criterion.ItemPredicate",
            "net.minecraft.advancements.predicates.ItemPredicate"};

    private static final Map<Object, Object> CODECS = Collections.synchronizedMap(new WeakHashMap<>());
    // The ops the advancement loader is reading with. Predicates hold registry entries since
    // 1.20.5, and only these registry-aware ops can resolve them.
    private static final ThreadLocal<Object> READING_OPS = new ThreadLocal<>();

    private LegacyCriteria() {}

    // ---- triggers -----------------------------------------------------------------------------

    /** {@code CriteriaTriggers.register(trigger)}: the trigger types registry, under the trigger's own id. */
    public static Object register(Object trigger) throws Throwable {
        Method getId = trigger.getClass().getMethod("getId");
        getId.trySetAccessible();
        Object id = getId.invoke(trigger);
        Object types = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader())
                .getField("TRIGGER_TYPES").get(null);
        LateVanillaRegistration.register(types, id, trigger);
        return trigger;
    }

    /** A trigger's codec: an advancement's JSON goes to the mod's own {@code createInstance(json, context)}. */
    public static Object codec(Object trigger) {
        return CODECS.computeIfAbsent(trigger, LegacyCriteria::buildCodec);
    }

    private static Object buildCodec(Object trigger) {
        try {
            ClassLoader loader = loader();
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
            Class<?> jsonElement = Class.forName(GSON + "JsonElement", true, loader);
            Method create = method(trigger.getClass(), "createInstance", 2, jsonObject);
            Function<Object, Object> decode = input -> {
                Object previous = READING_OPS.get();
                try {
                    Object json = value.invoke(convert.invoke(input, jsonOps));
                    if (!jsonObject.isInstance(json)) {
                        return error.invoke(null, (Supplier<String>) () -> "expected an object, not " + json);
                    }
                    // Only ops that read JSON can read the JSON the trigger hands its predicates.
                    READING_OPS.set(jsonElement.isInstance(value.invoke(input)) ? getOps.invoke(input) : null);
                    return success.invoke(null, create.invoke(trigger, json, null));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Throwable cause = e instanceof InvocationTargetException wrapped ? wrapped.getCause() : e;
                    return failure(error, trigger, cause);
                } finally {
                    READING_OPS.set(previous);
                }
            };
            Function<Object, Object> encode = instance -> {
                try {
                    Object json = method(instance.getClass(), "serializeToJson", 1, null).invoke(instance, (Object) null);
                    return success.invoke(null, dynamic.getConstructor(ops, Object.class).newInstance(jsonOps, json));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Throwable cause = e instanceof InvocationTargetException wrapped ? wrapped.getCause() : e;
                    return failure(error, trigger, cause);
                }
            };
            return codec.getMethod("flatXmap", Function.class, Function.class)
                    .invoke(codec.getField("PASSTHROUGH").get(null), decode, encode);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build the codec of " + trigger.getClass().getName(), e);
        }
    }

    // ---- entity predicates --------------------------------------------------------------------

    /** The {@code player} predicate an old trigger's JSON names, or null for any player. */
    public static Object player(Object json) {
        Object element = get(json, "player");
        return element == null ? null : parsePlayer(json, element);
    }

    /** {@code EntityPredicate.fromJson(json, key, context)}: absent reads as any entity, as it did. */
    public static Object fromJson(Object json, String key, Object context) {
        Object element = get(json, key);
        return element == null ? any() : parsePlayer(json, element);
    }

    /** Reads a player predicate: an entity predicate until 26.3, a loot condition holder from 26.3. */
    private static Object parsePlayer(Object json, Object element) {
        return hasContextPredicate()
                ? parse(json, ENTITY_PREDICATE, "ADVANCEMENT_CODEC", element)
                : parse(json, LOOT_CONDITION, "CODEC", element);
    }

    private static boolean hasContextPredicate() {
        try {
            first(loader(), CONTEXT_PREDICATE);
            return true;
        } catch (ClassNotFoundException removed) {
            return false;
        }
    }

    /** {@code ContextAwarePredicate.ANY}: a predicate with no conditions, which every entity passes. */
    public static Object any() {
        try {
            if (!hasContextPredicate()) return anyCondition();
            Class<?> predicate = first(loader(), CONTEXT_PREDICATE);
            for (Method method : predicate.getMethods()) {
                if (method.getName().equals("create") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isArray()) {
                    return method.invoke(null, Array.newInstance(method.getParameterTypes()[0].getComponentType(), 0));
                }
            }
            throw new NoSuchMethodException("ContextAwarePredicate.create");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build an empty entity predicate", e);
        }
    }

    /** 26.3's empty predicate: a holder of an all_of condition with nothing in it, which every entity passes. */
    private static Object anyCondition() throws ReflectiveOperationException {
        ClassLoader loader = loader();
        Class<?> holderSet = Class.forName("net.minecraft.core.HolderSet", true, loader);
        Object none = holderSet.getMethod("direct", java.util.List.class).invoke(null, java.util.List.of());
        Object allOf = Class.forName("net.minecraft.world.level.storage.loot.predicates.AllOfCondition", true, loader)
                .getMethod("allOf", holderSet).invoke(null, none);
        return Class.forName("net.minecraft.core.Holder", true, loader).getMethod("direct", Object.class).invoke(null, allOf);
    }

    /** {@code ContextAwarePredicate.toJson(context)}. */
    public static Object toJson(Object predicate) {
        return encode(predicate, hasContextPredicate() ? CONTEXT_PREDICATE : LOOT_CONDITION, "CODEC");
    }

    // ---- item predicates ----------------------------------------------------------------------

    /** {@code ItemPredicate.fromJson(element)}: absent or null reads as any item, as it did. */
    public static Object itemFromJson(Object element) {
        try {
            if (element == null || Boolean.TRUE.equals(element.getClass().getMethod("isJsonNull").invoke(element))) {
                return itemAny();
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return parse(element, ITEM_PREDICATE, "CODEC", element);
    }

    /** {@code ItemPredicate.ANY}: a predicate every item passes. */
    public static Object itemAny() {
        try {
            Class<?> builder = first(loader(),
                    new String[]{ITEM_PREDICATE[0] + "$Builder", ITEM_PREDICATE[1] + "$Builder", ITEM_PREDICATE[2] + "$Builder"});
            Object empty = builder.getMethod("item").invoke(null);
            return builder.getMethod("build").invoke(empty);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build an empty item predicate", e);
        }
    }

    /** {@code ItemPredicate.serializeToJson()}. */
    public static Object itemToJson(Object predicate) {
        return encode(predicate, ITEM_PREDICATE, "CODEC");
    }

    // ---- codec plumbing -----------------------------------------------------------------------

    private static Object parse(Object anchor, String[] owner, String codecField, Object element) {
        try {
            ClassLoader loader = loader();
            Object codec = first(loader, owner).getField(codecField).get(null);
            Object ops = READING_OPS.get();
            if (ops == null) ops = Class.forName(SERIALIZATION + "JsonOps", true, loader).getField("INSTANCE").get(null);
            // Through the public interfaces: a codec's and a result's own classes are usually not public.
            Method parse = Class.forName(SERIALIZATION + "Decoder", true, loader)
                    .getMethod("parse", Class.forName(SERIALIZATION + "DynamicOps", true, loader), Object.class);
            Object result = parse.invoke(codec, ops, element);
            Class<?> dataResult = Class.forName(SERIALIZATION + "DataResult", true, loader);
            Optional<?> value = (Optional<?>) dataResult.getMethod("result").invoke(result);
            if (value.isPresent()) return value.get();
            throw new IllegalArgumentException("Could not read " + element + ": " + dataResult.getMethod("error").invoke(result));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the predicate " + element, e);
        }
    }

    private static Object encode(Object value, String[] owner, String codecField) {
        try {
            ClassLoader loader = loader();
            Object codec = first(loader, owner).getField(codecField).get(null);
            Object jsonOps = Class.forName(SERIALIZATION + "JsonOps", true, loader).getField("INSTANCE").get(null);
            Method encode = Class.forName(SERIALIZATION + "Encoder", true, loader)
                    .getMethod("encodeStart", Class.forName(SERIALIZATION + "DynamicOps", true, loader), Object.class);
            Object result = encode.invoke(codec, jsonOps, value);
            Optional<?> json = (Optional<?>) Class.forName(SERIALIZATION + "DataResult", true, loader)
                    .getMethod("result").invoke(result);
            if (json.isPresent()) return json.get();
            return Class.forName(GSON + "JsonNull", true, loader).getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not write the predicate " + value, e);
        }
    }

    private static Object get(Object json, String key) {
        try {
            return json.getClass().getMethod("get", String.class).invoke(json, key);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A public method by name and arity, with its first parameter of {@code firstParameter} when given. */
    private static Method method(Class<?> type, String name, int arity, Class<?> firstParameter) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arity
                    && (firstParameter == null || method.getParameterTypes()[0] == firstParameter)) {
                method.trySetAccessible();
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Class<?> first(ClassLoader loader, String[] names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name, true, loader);
            } catch (ClassNotFoundException notThisHost) {
                // the class sits in another package on this host
            }
        }
        throw new ClassNotFoundException(names[0]);
    }

    /** This class's loader: it is embedded in the mod, so it sees Minecraft and the game's Gson alike. */
    private static ClassLoader loader() {
        return LegacyCriteria.class.getClassLoader();
    }

    private static Object failure(Method error, Object trigger, Throwable cause) {
        try {
            return error.invoke(null, (Supplier<String>) () -> trigger.getClass().getName() + ": " + cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
