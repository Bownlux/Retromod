/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.registry;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Forge {@code IForgeRegistry} and registry-id buffer calls that have no same-shaped method on a
 * vanilla {@code Registry}, embedded per mod.
 *
 * <p>On NeoForge every registry a Forge mod reaches, built-in or created through
 * {@link LegacyRegistryBuilder}, is a vanilla {@code Registry}. Each method takes that registry as
 * its first argument. Host calls go through reflection because Retromod is not compiled against
 * Minecraft.
 */
public final class LegacyRegistryAccess {

    // Later hosts renamed these members. Class moves rename this class's descriptors when it is
    // embedded, but not the method names it looks up here, so each lookup lists the old name first.
    private static final String[] HOLDER_LOOKUPS = {"getHolder", "get"};
    private static final String[] VALUE_LOOKUPS = {"getValue", "get"};
    private static final String[] KEY_LOCATIONS = {"location", "identifier"};
    private static final String[] WRITE_LOCATIONS = {"writeResourceLocation", "writeIdentifier"};
    private static final String[] READ_LOCATIONS = {"readResourceLocation", "readIdentifier"};
    private static final Predicate<Method> ANY = method -> true;
    private static final Predicate<Method> RETURNS_OPTIONAL =
            method -> method.getReturnType() == Optional.class;
    private static final Predicate<Method> RETURNS_VALUE = RETURNS_OPTIONAL.negate();

    private LegacyRegistryAccess() {}

    /** {@code getHolder(value)} and {@code getDelegate(value)}: the value's reference holder. */
    public static Optional<Object> holderOf(Object registry, Object value) {
        Optional<?> key = (Optional<?>) call(registry, "getResourceKey", value);
        if (key.isEmpty()) return Optional.empty();
        return holderByKey(registry, key.get());
    }

    /** {@code getDelegateOrThrow(value)}. */
    public static Object holderOfOrThrow(Object registry, Object value) {
        return holderOf(registry, value).orElseThrow(() -> new NoSuchElementException(
                "No holder for " + value + " in registry " + registryName(registry)));
    }

    /**
     * {@code getHolder(key)} and {@code getDelegate(key)} for a resource location or key. 1.21.2
     * renamed {@code getHolder} to {@code get}, and only the {@code Optional} form is the holder.
     */
    public static Optional<Object> holderByKey(Object registry, Object key) {
        @SuppressWarnings("unchecked")
        Optional<Object> holder = (Optional<Object>) call(registry, HOLDER_LOOKUPS, RETURNS_OPTIONAL, key);
        return holder;
    }

    /** {@code getDelegateOrThrow(key)}. */
    public static Object holderByKeyOrThrow(Object registry, Object key) {
        return holderByKey(registry, key).orElseThrow(() -> new NoSuchElementException(
                "No holder for " + key + " in registry " + registryName(registry)));
    }

    /** {@code getRegistryName()}: the location of the registry's key. */
    public static Object registryName(Object registry) {
        return call(call(registry, "key"), KEY_LOCATIONS, ANY, new Object[0]);
    }

    /** {@code getRegistryKey()}. */
    public static Object registryKey(Object registry) {
        return call(registry, "key");
    }

    /** {@code getResourceKey(value)}. */
    public static Optional<?> resourceKey(Object registry, Object value) {
        return (Optional<?>) call(registry, "getResourceKey", value);
    }

    /** {@code getDefaultKey()}: only a defaulted registry has one. */
    public static Object defaultKey(Object registry) {
        return findMethod(registry.getClass(), "getDefaultKey", 0) == null
                ? null : call(registry, "getDefaultKey");
    }

    /** {@code containsValue(value)}. */
    public static boolean containsValue(Object registry, Object value) {
        return resourceKey(registry, value).isPresent();
    }

    /** {@code isEmpty()}. */
    public static boolean isEmpty(Object registry) {
        return !((Iterable<?>) registry).iterator().hasNext();
    }

    /** {@code getCodec()}: the registry's by-name codec. */
    public static Object codec(Object registry) {
        return call(registry, "byNameCodec");
    }

    /** {@code iterator()}. */
    public static Iterator<?> iterator(Object registry) {
        return ((Iterable<?>) registry).iterator();
    }

    /**
     * {@code FriendlyByteBuf.writeRegistryId(registry, value)}: the registry name, then the value's
     * numeric id, read back by {@link #readRegistryId}. Both sides run the same translated mod, so
     * the format only has to agree with itself.
     */
    public static void writeRegistryId(Object buffer, Object registry, Object value) {
        call(buffer, WRITE_LOCATIONS, ANY, registryName(registry));
        writeRegistryIdUnsafe(buffer, registry, value);
    }

    /** {@code FriendlyByteBuf.readRegistryId()}. */
    public static Object readRegistryId(Object buffer) {
        Object name = call(buffer, READ_LOCATIONS, ANY, new Object[0]);
        Object registry = registryByName(name);
        if (registry == null) {
            throw new IllegalStateException("A packet named the unknown registry " + name);
        }
        return readRegistryIdUnsafe(buffer, registry);
    }

    /** {@code FriendlyByteBuf.readRegistryIdSafe(type)}. */
    public static Object readRegistryIdSafe(Object buffer, Class<?> type) {
        Object value = readRegistryId(buffer);
        if (value != null && !type.isInstance(value)) {
            throw new IllegalArgumentException("A packet held a " + value.getClass().getName()
                    + " where a " + type.getName() + " was expected");
        }
        return value;
    }

    /** {@code FriendlyByteBuf.writeRegistryIdUnsafe(registry, value)}: the numeric id only. */
    public static void writeRegistryIdUnsafe(Object buffer, Object registry, Object value) {
        call(buffer, "writeVarInt", call(registry, "getId", value));
    }

    /** {@code FriendlyByteBuf.writeRegistryIdUnsafe(registry, location)}. */
    public static void writeRegistryIdUnsafeByName(Object buffer, Object registry, Object location) {
        writeRegistryIdUnsafe(buffer, registry, valueByName(registry, location));
    }

    /** {@code FriendlyByteBuf.readRegistryIdUnsafe(registry)}. */
    public static Object readRegistryIdUnsafe(Object buffer, Object registry) {
        return call(registry, "byId", call(buffer, "readVarInt"));
    }

    private static Object registryByName(Object name) {
        try {
            Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries",
                    false, LegacyRegistryAccess.class.getClassLoader());
            Field root = builtIn.getField("REGISTRY");
            return valueByName(root.get(null), name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the root registry: " + e, e);
        }
    }

    /**
     * The value a registry holds under a name. Before 1.21.2 that is {@code get(name)}; 1.21.2 made
     * {@code get} return the holder and moved the value to {@code getValue}.
     */
    private static Object valueByName(Object registry, Object name) {
        return call(registry, VALUE_LOOKUPS, RETURNS_VALUE, name);
    }

    private static Object call(Object target, String name, Object... args) {
        return call(target, new String[] {name}, ANY, args);
    }

    /**
     * Calls the first public method, in the order of {@code names}, whose parameters accept the
     * arguments and that {@code shape} admits. Overloads such as {@code getHolder(ResourceLocation)}
     * and {@code getHolder(ResourceKey)} are told apart by the runtime argument types.
     */
    private static Object call(Object target, String[] names, Predicate<Method> shape, Object... args) {
        Method method = null;
        for (int i = 0; i < names.length && method == null; i++) {
            method = findMethod(target.getClass(), names[i], shape, args);
        }
        if (method == null) {
            throw new IllegalStateException("Retromod could not find " + String.join(" or ", names)
                    + " on " + target.getClass().getName() + " for a Forge registry call");
        }
        String name = method.getName();
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not call " + name + ": " + e, e);
        }
    }

    private static Method findMethod(Class<?> type, String name, int arity) {
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == arity) {
                return candidate;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Predicate<Method> shape, Object[] args) {
        for (Method candidate : type.getMethods()) {
            if (!candidate.getName().equals(name) || candidate.getParameterCount() != args.length
                    || !shape.test(candidate)) {
                continue;
            }
            Class<?>[] params = candidate.getParameterTypes();
            boolean accepts = true;
            for (int i = 0; i < params.length && accepts; i++) {
                accepts = accepts(params[i], args[i]);
            }
            if (accepts) return candidate;
        }
        return null;
    }

    private static boolean accepts(Class<?> param, Object arg) {
        if (arg == null) return !param.isPrimitive();
        if (param == int.class) return arg instanceof Integer;
        if (param == boolean.class) return arg instanceof Boolean;
        return param.isInstance(arg);
    }
}
