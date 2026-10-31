/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** NeoForge 1.21.1's NBT save methods on item, fluid, and energy handlers, run through the ValueIO ones that replaced them in 1.21.6. */
public final class LegacyHandlerNbt {

    static final String ITEM_HANDLER = "net.neoforged.neoforge.items.ItemStackHandler";
    static final String FLUID_TANK = "net.neoforged.neoforge.fluids.capability.templates.FluidTank";
    static final String ENERGY_STORAGE = "net.neoforged.neoforge.energy.EnergyStorage";

    // A mod handler's own override of an old method, per class and method, found once. A plain map,
    // since the embedder copies this class alone and an anonymous ClassValue would be left behind.
    private static final Map<Class<?>, Map<String, Optional<Method>>> OVERRIDES = new ConcurrentHashMap<>();

    private LegacyHandlerNbt() {}

    // Each call runs the mod's own override when its handler declares one, as the virtual call
    // did. That override's super call goes to the super$ twin, which only saves or loads.

    /** {@code handler.serializeNBT(provider)}: the handler's slots as the compound 1.21.1 wrote. */
    public static Object serializeNBT(Object handler, Object provider) {
        Method override = override(handler, ITEM_HANDLER, "serializeNBT", 1);
        return override != null ? invoke(override, handler, provider) : super$serializeNBT(handler, provider);
    }

    public static Object super$serializeNBT(Object handler, Object provider) {
        return save(handler, provider);
    }

    /** {@code handler.deserializeNBT(provider, tag)}. */
    public static void deserializeNBT(Object handler, Object provider, Object tag) {
        Method override = override(handler, ITEM_HANDLER, "deserializeNBT", 2);
        if (override != null) invoke(override, handler, provider, tag);
        else super$deserializeNBT(handler, provider, tag);
    }

    public static void super$deserializeNBT(Object handler, Object provider, Object tag) {
        load(handler, provider, tag);
    }

    /** {@code tank.writeToNBT(provider, tag)}: the fluid added to the given compound, which is returned. */
    public static Object writeToNBT(Object tank, Object provider, Object tag) {
        Method override = override(tank, FLUID_TANK, "writeToNBT", 2);
        return override != null ? invoke(override, tank, provider, tag) : super$writeToNBT(tank, provider, tag);
    }

    public static Object super$writeToNBT(Object tank, Object provider, Object tag) {
        return call(tag, "merge", save(tank, provider));
    }

    /** {@code tank.readFromNBT(provider, tag)}, which returns the tank. */
    public static Object readFromNBT(Object tank, Object provider, Object tag) {
        Method override = override(tank, FLUID_TANK, "readFromNBT", 2);
        return override != null ? invoke(override, tank, provider, tag) : super$readFromNBT(tank, provider, tag);
    }

    public static Object super$readFromNBT(Object tank, Object provider, Object tag) {
        load(tank, provider, tag);
        return tank;
    }

    /** {@code storage.serializeNBT(provider)}: 1.21.1 saved the energy as a bare int tag. */
    public static Object energySerializeNBT(Object storage, Object provider) {
        Method override = override(storage, ENERGY_STORAGE, "serializeNBT", 1);
        return override != null ? invoke(override, storage, provider) : super$energySerializeNBT(storage, provider);
    }

    public static Object super$energySerializeNBT(Object storage, Object provider) {
        try {
            return Class.forName("net.minecraft.nbt.IntTag", true, loader()).getMethod("valueOf", int.class)
                    .invoke(null, (int) call(storage, "getEnergyStored"));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not save an old energy storage", failure);
        }
    }

    /** {@code storage.deserializeNBT(provider, tag)}, read from the int tag 1.21.1 saved. */
    public static void energyDeserializeNBT(Object storage, Object provider, Object tag) {
        Method override = override(storage, ENERGY_STORAGE, "deserializeNBT", 2);
        if (override != null) invoke(override, storage, provider, tag);
        else super$energyDeserializeNBT(storage, provider, tag);
    }

    public static void super$energyDeserializeNBT(Object storage, Object provider, Object tag) {
        Optional<?> energy = (Optional<?>) call(tag, "asInt");
        if (energy.isEmpty()) throw new IllegalArgumentException("An old energy storage can only load an int tag, not " + tag);
        try {
            Object compound = Class.forName("net.minecraft.nbt.CompoundTag", true, loader()).getConstructor().newInstance();
            call(compound, "putInt", "energy", energy.get());
            load(storage, provider, compound);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not load an old energy storage", failure);
        }
    }

    // The handler writes itself through ValueIO; its keys are the ones 1.21.1 wrote, so old saves still load.
    private static Object save(Object handler, Object provider) {
        try {
            ClassLoader loader = loader();
            Class<?> output = Class.forName("net.minecraft.world.level.storage.TagValueOutput", true, loader);
            Object values = output.getMethod("createWithContext", Class.forName("net.minecraft.util.ProblemReporter", true, loader),
                    Class.forName("net.minecraft.core.HolderLookup$Provider", true, loader))
                    .invoke(null, problemReporter(loader), provider);
            call(handler, "serialize", values);
            return call(values, "buildResult");
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not save an old handler", failure);
        }
    }

    private static void load(Object handler, Object provider, Object compound) {
        try {
            ClassLoader loader = loader();
            Class<?> input = Class.forName("net.minecraft.world.level.storage.TagValueInput", true, loader);
            Object values = input.getMethod("create", Class.forName("net.minecraft.util.ProblemReporter", true, loader),
                    Class.forName("net.minecraft.core.HolderLookup$Provider", true, loader),
                    Class.forName("net.minecraft.nbt.CompoundTag", true, loader))
                    .invoke(null, problemReporter(loader), provider, compound);
            call(handler, "deserialize", values);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not load an old handler", failure);
        }
    }

    private static Object problemReporter(ClassLoader loader) throws ReflectiveOperationException {
        return Class.forName("net.minecraft.util.ProblemReporter", true, loader).getField("DISCARDING").get(null);
    }

    /** The mod's own override of the old method, or null when its handler declares none. */
    static Method override(Object handler, String host, String name, int arity) {
        return OVERRIDES.computeIfAbsent(handler.getClass(), unused -> new ConcurrentHashMap<>())
                .computeIfAbsent(host + "#" + name, unused -> Optional.ofNullable(findOverride(handler.getClass(), host, name, arity)))
                .orElse(null);
    }

    private static Method findOverride(Class<?> type, String host, String name, int arity) {
        for (Class<?> current = type; current != null && !current.getName().equals(host); current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (isOldMethod(method, name, arity)) return method;
            }
        }
        return null;
    }

    // Javac's bridge methods forward to the real override, so they are not one.
    private static boolean isOldMethod(Method method, String name, int arity) {
        return method.getName().equals(name) && method.getParameterCount() == arity && !method.isBridge()
                && method.getParameterTypes()[0].getName().endsWith("HolderLookup$Provider");
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            method.trySetAccessible();
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A public method by name and arity whose parameters accept the arguments. */
    private static Object call(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            Class<?>[] parameters = method.getParameterTypes();
            boolean fits = true;
            for (int i = 0; i < parameters.length && fits; i++) {
                fits = arguments[i] == null ? !parameters[i].isPrimitive() : boxed(parameters[i]).isInstance(arguments[i]);
            }
            if (fits) return invoke(method, target, arguments);
        }
        throw new IllegalStateException(target.getClass().getName() + " has no " + name + " for " + arguments.length + " arguments");
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }

    private static ClassLoader loader() {
        return LegacyHandlerNbt.class.getClassLoader();
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }
}
