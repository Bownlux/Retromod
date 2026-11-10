/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Converts a codec an old mod registers into a registry that holds map codecs since 1.20.5, on the
 * deferred register and register event paths of Forge and NeoForge.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft. The
 * caller passes the conversion, which a generated class beside this one implements.
 */
public final class LegacyLootModifierCodecs {

    // Registries whose values Forge 1.20.1, NeoForge 1.20.4 and Minecraft 1.20.4 typed Codec and
    // 1.20.5 and later type MapCodec. 26.3 renamed the two surface rule registries, and Retromod
    // points an old mod's key at the new name. Structure processor types became map codecs in 26.2;
    // the converter changes them only on a host where the registry holds map codecs.
    private static final Set<String> MAP_CODEC_REGISTRIES = Set.of(
            "forge:global_loot_modifier_serializers", "forge:biome_modifier_serializers",
            "forge:structure_modifier_serializers",
            "neoforge:global_loot_modifier_serializers", "neoforge:biome_modifier_serializers",
            "neoforge:structure_modifier_serializers", "neoforge:condition_codecs",
            "minecraft:worldgen/chunk_generator", "minecraft:worldgen/biome_source",
            "minecraft:worldgen/density_function_type", "minecraft:worldgen/material_condition",
            "minecraft:worldgen/material_rule", "minecraft:worldgen/material_condition_type",
            "minecraft:worldgen/material_rule_type", "minecraft:worldgen/structure_processor");

    private static final String PROCESSOR_TYPE = "net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType";

    private LegacyLootModifierCodecs() {}

    /** Whether the registry a key names holds map codecs. */
    public static boolean holdsMapCodecs(Object registryKey) {
        // A registry's key prints as ResourceKey[minecraft:root / <registry>] on every version.
        String key = String.valueOf(registryKey);
        int separator = key.lastIndexOf(" / ");
        if (!key.startsWith("ResourceKey[") || separator < 0 || !key.endsWith("]")) return false;
        return MAP_CODEC_REGISTRIES.contains(key.substring(separator + 3, key.length() - 1));
    }

    /** The supplier to register: converting what it builds for a map codec registry, unchanged otherwise. */
    public static Supplier<?> serializer(Object registryKey, Supplier<?> supplier, Function<Object, Object> convert) {
        if (!holdsMapCodecs(registryKey)) return supplier;
        return () -> convert.apply(supplier.get());
    }

    /** {@code deferredRegister.register(name, supplier)}, converting for a map codec registry. */
    public static Object registerDeferred(Object deferredRegister, String name, Supplier<?> supplier,
            Function<Object, Object> convert) throws Throwable {
        Object registryKey = call(deferredRegister, "getRegistryKey", new Class<?>[0]);
        Supplier<?> registered = serializer(registryKey, supplier, convert);
        return call(deferredRegister, "register", new Class<?>[] {String.class, Supplier.class}, name, registered);
    }

    /** {@code event.register(registryKey, consumer)}, whose helper converts what it registers into a map codec registry. */
    public static void registerEach(Object event, Object registryKey, Consumer<Object> consumer,
            Function<Object, Object> convert) throws Throwable {
        Consumer<Object> registering = holdsMapCodecs(registryKey)
                ? helper -> consumer.accept(converting(helper, convert)) : consumer;
        Method register = null;
        for (Method method : LegacyPublicMethods.methods(event.getClass())) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("register") && parameters.length == 2 && parameters[1] == Consumer.class) {
                register = method;
            }
        }
        if (register == null) {
            throw new NoSuchMethodException(event.getClass().getName() + ".register(ResourceKey, Consumer)");
        }
        invoke(register, event, registryKey, registering);
    }

    /** What an old structure processor type's {@code codec()} returns, or null when the value is none or its codec cannot be read. */
    public static Object processorTypeCodec(Object value) {
        // By name, so a host without the type never has to load it.
        return value != null && implementsNamed(value.getClass(), PROCESSOR_TYPE) ? typeCodec(value) : null;
    }

    private static boolean implementsNamed(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> implemented : current.getInterfaces()) {
                if (implemented.getName().equals(name) || implementsNamed(implemented, name)) return true;
            }
        }
        return false;
    }

    /** What a type lambda's {@code codec()} returns, or null when it cannot be read. */
    private static Object typeCodec(Object type) {
        try {
            // The lambda's class is not public, and this copy shares the mod's module, so it may open the method.
            Method codec = type.getClass().getMethod("codec");
            codec.setAccessible(true);
            return codec.invoke(type);
        } catch (InvocationTargetException failed) {
            unread(type, failed.getCause());
        } catch (ReflectiveOperationException | RuntimeException failed) {
            unread(type, failed);
        }
        return null;
    }

    private static void unread(Object type, Throwable cause) {
        LegacyLog.warn("Could not read the codec of " + type.getClass().getName() + ", so it stays registered as the mod built it: "
                + cause);
    }

    /** The register event's helper, with each value it registers converted first. */
    static Object converting(Object helper, Function<Object, Object> convert) {
        Class<?> helperType = helperInterface(helper.getClass());
        if (helperType == null) return helper;
        // Every helper method takes the value last, and the default ones pass it on to register(id, value).
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("register") && arguments != null && arguments.length == 2) {
                arguments = new Object[] {arguments[0], convert.apply(arguments[1])};
            }
            return invoke(method, helper, arguments);
        };
        return Proxy.newProxyInstance(helperType.getClassLoader(), new Class<?>[] {helperType}, handler);
    }

    /** The loader's RegisterHelper interface a helper implements, or null. */
    private static Class<?> helperInterface(Class<?> type) {
        for (Class<?> candidate : type.getInterfaces()) {
            if (candidate.getName().endsWith("RegisterEvent$RegisterHelper")) return candidate;
        }
        return null;
    }

    private static Object call(Object target, String name, Class<?>[] parameters, Object... arguments) throws Throwable {
        return invoke(LegacyPublicMethods.method(target.getClass(), name, parameters), target, arguments);
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }
}
