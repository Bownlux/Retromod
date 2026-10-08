/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.registry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Stand-in for Forge's {@code RegistryBuilder}, embedded into a Forge mod under the Forge name.
 *
 * <p>NeoForge's builder takes the registry key in its constructor and has no {@code setName}, so
 * a Forge mod's {@code new RegistryBuilder<>().setName(...)} cannot link against it. This class
 * records the options a Forge mod sets and replays the ones NeoForge still has (default key,
 * maximum id, network sync) onto NeoForge's builder when the registry is created.
 *
 * <p>Parameters typed {@code Object} here are retyped to Forge's descriptors by
 * {@link com.retromod.shim.forge.LegacyCustomRegistryBridge}. The bodies only store them, so the
 * retyping is verifier-safe. Retromod is not compiled against Minecraft, so every host call goes
 * through reflection.
 */
public class LegacyRegistryBuilder {

    // 1.21.11 renamed ResourceLocation to Identifier. Class moves rename this class's descriptors
    // when it is embedded, but not the class names it looks up reflectively.
    private static final String[] LOCATION_CLASSES = {
            "net.minecraft.resources.ResourceLocation", "net.minecraft.resources.Identifier"};
    private static final String RESOURCE_KEY = "net.minecraft.resources.ResourceKey";
    private static final String NEO_BUILDER = "net.neoforged.neoforge.registries.RegistryBuilder";

    private Object name;
    private Object defaultKey;
    private int maxId = -1;
    // Forge synced custom registries unless the mod opted out; NeoForge defaults to not syncing.
    private boolean sync = true;

    public LegacyRegistryBuilder() {}

    public static LegacyRegistryBuilder of() {
        return new LegacyRegistryBuilder();
    }

    public static LegacyRegistryBuilder of(String name) {
        return new LegacyRegistryBuilder().setName(location(name));
    }

    public static LegacyRegistryBuilder of(Object name) {
        return new LegacyRegistryBuilder().setName(name);
    }

    public LegacyRegistryBuilder setName(Object name) {
        this.name = name;
        return this;
    }

    public LegacyRegistryBuilder setIDRange(int min, int max) {
        this.maxId = max;
        return this;
    }

    public LegacyRegistryBuilder setMaxID(int max) {
        this.maxId = max;
        return this;
    }

    public LegacyRegistryBuilder setDefaultKey(Object key) {
        this.defaultKey = key;
        return this;
    }

    // Forge's registry callbacks have no NeoForge counterpart with the same contract. They are
    // accepted so the builder chain links, and are not called.
    public LegacyRegistryBuilder addCallback(Object callback) {
        return this;
    }

    public LegacyRegistryBuilder onAdd(Object callback) {
        return this;
    }

    public LegacyRegistryBuilder onClear(Object callback) {
        return this;
    }

    public LegacyRegistryBuilder onCreate(Object callback) {
        return this;
    }

    public LegacyRegistryBuilder onValidate(Object callback) {
        return this;
    }

    public LegacyRegistryBuilder onBake(Object callback) {
        return this;
    }

    public LegacyRegistryBuilder missing(Object factory) {
        return this;
    }

    // NeoForge saves every registry and has no override or modification locks.
    public LegacyRegistryBuilder disableSaving() {
        return this;
    }

    public LegacyRegistryBuilder disableSync() {
        this.sync = false;
        return this;
    }

    public LegacyRegistryBuilder disableOverrides() {
        return this;
    }

    public LegacyRegistryBuilder allowModification() {
        return this;
    }

    // Every NeoForge registry supports tags.
    public LegacyRegistryBuilder hasTags() {
        return this;
    }

    public LegacyRegistryBuilder legacyName(String legacyName) {
        return this;
    }

    public LegacyRegistryBuilder legacyName(Object legacyName) {
        return this;
    }

    /**
     * Forge's {@code NewRegistryEvent.create(builder)}: creates and registers the registry through
     * NeoForge's event, and returns a supplier of it in place of Forge's {@code IForgeRegistry}.
     */
    public static Supplier<Object> retromod$create(Object event, LegacyRegistryBuilder builder) {
        return retromod$create(event, builder, null);
    }

    /** Forge's {@code NewRegistryEvent.create(builder, onCreated)}. */
    public static Supplier<Object> retromod$create(Object event, LegacyRegistryBuilder builder,
                                                   Consumer<Object> onCreated) {
        if (builder.name == null) {
            throw new IllegalStateException("A Forge RegistryBuilder reached NewRegistryEvent.create "
                    + "without a name. The mod must call setName before creating the registry.");
        }
        Object neoBuilder = builder.toNeoForge();
        Object registry = invoke(event, "create", new Class<?>[] {neoBuilder.getClass()}, neoBuilder);
        if (onCreated != null) onCreated.accept(registry);
        return () -> registry;
    }

    /**
     * Forge's {@code DeferredRegister.makeRegistry(builderSupplier)}. NeoForge's version takes a
     * consumer of its own builder, which already carries the deferred register's key.
     */
    public static Supplier<Object> retromod$makeRegistry(Object deferredRegister,
                                                         Supplier<LegacyRegistryBuilder> builder) {
        Consumer<Object> configure = neoBuilder -> builder.get().applyTo(neoBuilder);
        Object registry = invoke(deferredRegister, "makeRegistry",
                new Class<?>[] {Consumer.class}, configure);
        return () -> registry;
    }

    private Object toNeoForge() {
        try {
            ClassLoader loader = loader();
            Class<?> locationClass = locationClass();
            Class<?> keyClass = Class.forName(RESOURCE_KEY, false, loader);
            Object key = keyClass.getMethod("createRegistryKey", locationClass).invoke(null, name);
            Object neoBuilder = Class.forName(NEO_BUILDER, false, loader).getConstructor(keyClass)
                    .newInstance(key);
            applyTo(neoBuilder);
            return neoBuilder;
        } catch (InvocationTargetException e) {
            throw rethrow("create the NeoForge registry builder for " + name, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw rethrow("create the NeoForge registry builder for " + name, e);
        }
    }

    private void applyTo(Object neoBuilder) {
        try {
            if (defaultKey != null) {
                invoke(neoBuilder, "defaultKey", new Class<?>[] {locationClass()}, defaultKey);
            }
            if (maxId >= 0) invoke(neoBuilder, "maxId", new Class<?>[] {int.class}, maxId);
            invoke(neoBuilder, "sync", new Class<?>[] {boolean.class}, sync);
        } catch (ClassNotFoundException e) {
            throw rethrow("configure the NeoForge registry builder for " + name, e);
        }
    }

    static Object location(String text) {
        try {
            return locationClass().getMethod("parse", String.class).invoke(null, text);
        } catch (InvocationTargetException e) {
            throw rethrow("parse the registry name " + text, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw rethrow("parse the registry name " + text, e);
        }
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method found = findPublic(target.getClass(), method, types);
            return found.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw rethrow("call " + method + " on " + target.getClass().getName(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw rethrow("call " + method + " on " + target.getClass().getName(), e);
        }
    }

    /** Looks up a public method whose parameters accept {@code types}, walking supertypes. */
    private static Method findPublic(Class<?> type, String name, Class<?>[] types)
            throws NoSuchMethodException {
        for (Method candidate : type.getMethods()) {
            if (!candidate.getName().equals(name)) continue;
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length != types.length) continue;
            boolean accepts = true;
            for (int i = 0; i < params.length && accepts; i++) {
                accepts = params[i].isAssignableFrom(types[i]);
            }
            if (accepts) return candidate;
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Class<?> locationClass() throws ClassNotFoundException {
        ClassNotFoundException missing = null;
        for (String name : LOCATION_CLASSES) {
            try {
                return Class.forName(name, false, loader());
            } catch (ClassNotFoundException e) {
                missing = e;
            }
        }
        throw missing;
    }

    private static ClassLoader loader() {
        return LegacyRegistryBuilder.class.getClassLoader();
    }

    private static RuntimeException rethrow(String action, Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Retromod could not " + action
                + " for a Forge custom registry: " + cause, cause);
    }
}
