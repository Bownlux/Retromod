/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.embedded;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Runtime half of {@link com.retromod.shim.fabric.Pre1_20_5ResourceConditionBridge}: registers a
 * pre-1.20.5 {@code ResourceConditions.register(Identifier, Predicate<JsonObject>)} condition
 * with the typed condition API Fabric API adopted with Minecraft 1.20.5.
 *
 * <p>The condition keeps the whole JSON object it was declared with, and its test hands that
 * object to the mod's predicate, which is what the old API did. The registry lookup the new API
 * passes is not given to the predicate, because the old one never had it.
 *
 * <p>Everything here is reflective. Retromod does not compile against DataFixerUpper or Fabric
 * API, and every transformed mod gets its own relocated copy of this class. An instance answers
 * one declared condition's proxy; it is not a nested class because only this class is embedded.
 */
public final class LegacyResourceConditionSupport implements InvocationHandler {

    private static final String API = "net.fabricmc.fabric.api.resource.conditions.v1.";
    private static final String CONDITION = API + "ResourceCondition";
    private static final String CONDITION_TYPE = API + "ResourceConditionType";
    private static final String CONDITIONS = API + "ResourceConditions";
    private static final String CODEC = "com.mojang.serialization.Codec";
    private static final String MAP_CODEC = "com.mojang.serialization.MapCodec";
    private static final String DYNAMIC = "com.mojang.serialization.Dynamic";
    private static final String DYNAMIC_OPS = "com.mojang.serialization.DynamicOps";
    private static final String JSON_OPS = "com.mojang.serialization.JsonOps";

    private final Object id;
    private final Predicate<Object> predicate;
    private final Object json;
    private final Object declaration;
    private final Object[] typeHolder;

    private LegacyResourceConditionSupport(Object id, Predicate<Object> predicate, Object json,
            Object declaration, Object[] typeHolder) {
        this.id = id;
        this.predicate = predicate;
        this.json = json;
        this.declaration = declaration;
        this.typeHolder = typeHolder;
    }

    /** {@code ResourceConditions.register(id, predicate)} in its pre-1.20.5 form. */
    public static void register(Object id, Predicate<Object> predicate) {
        try {
            ClassLoader loader = LegacyResourceConditionSupport.class.getClassLoader();
            Class<?> conditionClass = Class.forName(CONDITION, false, loader);
            Class<?> typeClass = Class.forName(CONDITION_TYPE, false, loader);
            Class<?> codecClass = Class.forName(CODEC, false, loader);
            Class<?> mapCodecClass = Class.forName(MAP_CODEC, false, loader);
            Class<?> dynamicClass = Class.forName(DYNAMIC, false, loader);
            Class<?> opsClass = Class.forName(DYNAMIC_OPS, false, loader);
            Object jsonOps = Class.forName(JSON_OPS, false, loader).getField("INSTANCE").get(null);
            Method convert = dynamicClass.getMethod("convert", opsClass);
            Method getValue = dynamicClass.getMethod("getValue");

            // The type and the conditions it decodes refer to each other, so the type is
            // published through this holder once it exists.
            Object[] typeHolder = new Object[1];
            Function<Object, Object> decode = dynamic -> {
                Object json = invoke(getValue, invoke(convert, dynamic, jsonOps));
                return Proxy.newProxyInstance(conditionClass.getClassLoader(),
                        new Class<?>[] {conditionClass},
                        new LegacyResourceConditionSupport(id, predicate, json, dynamic, typeHolder));
            };
            Function<Object, Object> encode = condition ->
                    ((LegacyResourceConditionSupport) Proxy.getInvocationHandler(condition)).declaration;

            Object passthrough = codecClass.getField("PASSTHROUGH").get(null);
            Object mapCodec = mapCodecClass.getMethod("assumeMapUnsafe", codecClass).invoke(null, passthrough);
            mapCodec = mapCodecClass.getMethod("xmap", Function.class, Function.class)
                    .invoke(mapCodec, decode, encode);
            Object type = typeClass.getMethod("create", id.getClass(), mapCodecClass).invoke(null, id, mapCodec);
            typeHolder[0] = type;
            Class.forName(CONDITIONS, false, loader).getMethod("register", typeClass).invoke(null, type);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod could not register the pre-1.20.5 resource "
                    + "condition " + id + " with the installed Fabric API, so the mod that declares "
                    + "it cannot finish loading. Report this with your Fabric API version.", e);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read a resource condition through " + method, e);
        }
    }

    /** Answers {@code getType} and {@code test} for one declared condition's proxy. */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "getType":
                return typeHolder[0];
            case "test":
                return predicate.test(json);
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "LegacyResourceCondition[" + id + "]";
            default:
                throw new UnsupportedOperationException("Pre-1.20.5 resource condition " + id
                        + " does not support " + method.getName());
        }
    }
}
