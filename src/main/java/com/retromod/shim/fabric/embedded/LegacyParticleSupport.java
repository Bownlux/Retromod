/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.embedded;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runtime half of {@link com.retromod.shim.fabric.Pre1_20_5ParticleTypeBridge}: calls a pre-1.20.5
 * mod's own particle serialization code by intermediary name.
 *
 * <p>The calls are reflective because every transformed mod gets its own relocated copy of the
 * generated particle classes. A particle type from one mod can therefore reach another mod's copy
 * of this helper, where a typed cast to the bridge interface would fail.
 */
public final class LegacyParticleSupport {

    /** Public field on the generated particle type base that holds the mod's old deserializer. */
    public static final String DESERIALIZER_FIELD = "retromod$legacyDeserializer";

    private static final String PARTICLE_OPTIONS = "net.minecraft.class_2394";
    private static final String WRITE_TO_NETWORK = "method_10294";
    private static final String FROM_COMMAND = "method_10296";
    private static final String FROM_NETWORK = "method_10297";

    private LegacyParticleSupport() {}

    /** {@code options.writeToNetwork(buf)}, which 1.20.5 removed from {@code ParticleOptions}. */
    public static void writeToNetwork(Object options, Object buf) {
        invoke(options, WRITE_TO_NETWORK, buf);
    }

    /** {@code type.getDeserializer().fromNetwork(type, buf)} for a bridged legacy particle type. */
    public static Object readFromNetwork(Object type, Object buf) {
        Object deserializer = deserializerOf(type);
        if (deserializer == null) {
            throw new IllegalStateException("Particle type " + type + " has no pre-1.20.5 "
                    + "deserializer, so Retromod cannot read it from the network");
        }
        return invoke(deserializer, FROM_NETWORK, type, buf);
    }

    /**
     * {@code type.getDeserializer().fromCommand(type, reader)}. A simple particle type is its own
     * options, which is what the removed vanilla deserializer returned for it.
     */
    public static Object readFromCommand(Object type, Object reader) {
        Object deserializer = deserializerOf(type);
        if (deserializer != null) {
            return invoke(deserializer, FROM_COMMAND, type, reader);
        }
        if (isParticleOptions(type)) {
            return type;
        }
        throw new IllegalArgumentException("Retromod cannot parse old command-style options for "
                + "particle type " + type + ". Only simple particles and particles from "
                + "pre-1.20.5 mods support this format.");
    }

    static Object deserializerOf(Object type) {
        if (type == null) return null;
        try {
            Field field = type.getClass().getField(DESERIALIZER_FIELD);
            return field.get(type);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

    private static boolean isParticleOptions(Object type) {
        try {
            ClassLoader loader = type.getClass().getClassLoader();
            return Class.forName(PARTICLE_OPTIONS, false, loader).isInstance(type);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static Object invoke(Object target, String name, Object... args) {
        if (target == null) {
            throw new IllegalStateException("Cannot call " + name + " on a missing particle object");
        }
        Method method = findMethod(target.getClass(), name, args);
        try {
            // Anonymous deserializers are package-private classes, so their public methods still
            // need an accessibility override before a call from another package.
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            // Rethrow the mod's own exception unchanged, including a checked
            // CommandSyntaxException that the caller expects from fromCommand.
            throw LegacyParticleSupport.<RuntimeException>sneakyThrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot call " + name + " on " + target.getClass().getName(), e);
        }
    }

    private static Method findMethod(Class<?> type, String name, Object[] args) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && accepts(method.getParameterTypes(), args)) {
                return method;
            }
        }
        throw new IllegalStateException(type.getName() + " has no " + name + " method for "
                + args.length + " argument(s), so its pre-1.20.5 particle data cannot be used");
    }

    private static boolean accepts(Class<?>[] parameters, Object[] args) {
        if (parameters.length != args.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            if (args[i] != null && !parameters[i].isInstance(args[i])) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
