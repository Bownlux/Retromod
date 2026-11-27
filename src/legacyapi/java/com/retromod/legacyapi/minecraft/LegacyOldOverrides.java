/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.item.ItemStack;

/** Finds and runs an old mod's own override of a hook the host reshaped, which no host code calls any more. */
public final class LegacyOldOverrides {

    /** What {@link #call} answers when the override threw, so the caller gives the host's default instead. */
    public static final Object FAILED = new Object();

    // Each class's old hook methods, looked up once: armor hooks run for every worn piece in every frame.
    private static final ClassValue<Map<Shape, Optional<Method>>> METHODS = new ClassValue<>() {
        @Override
        protected Map<Shape, Optional<Method>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
    // Overrides that threw once, by method and item; old code that reaches a removed API fails on every call.
    private static final Set<Broken> BROKEN = ConcurrentHashMap.newKeySet();
    // The overrides running on this thread, so a call that comes back to the same one answers the default instead of looping.
    private static final ThreadLocal<List<Object>> RUNNING = ThreadLocal.withInitial(ArrayList::new);

    private LegacyOldOverrides() {}

    /**
     * The public method {@code name} of {@code type} with these parameter types, declared by a mod class, or null.
     * A null parameter type matches any object type, for old types Retromod gives each mod its own copy of.
     */
    public static Method find(Class<?> type, String name, Class<?>... params) {
        if (type == null) return null;
        // Callers pass constant parameter arrays, so the array itself keys the lookup.
        return METHODS.get(type).computeIfAbsent(new Shape(name, params), shape -> Optional.ofNullable(search(type, name, params)))
                .orElse(null);
    }

    /** Runs the override, or answers {@link #FAILED} after logging once per class when it threw or failed before. */
    public static Object call(Object receiver, Method method, Object... args) {
        // A failure is remembered per item, so one item's broken branch leaves the class's other items working.
        Broken key = new Broken(method, itemOf(args));
        if (BROKEN.contains(key) || running(receiver, method)) return FAILED;
        List<Object> running = RUNNING.get();
        running.add(receiver);
        running.add(method);
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            // Old code that recursed without end fails for its item too; an out of memory error still stops the game.
            if (cause instanceof Error error && !(cause instanceof LinkageError) && !(cause instanceof StackOverflowError)) throw error;
            broke(key, cause);
            return FAILED;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            broke(key, failure);
            return FAILED;
        } finally {
            running.remove(running.size() - 1);
            running.remove(running.size() - 1);
        }
    }

    // Pairs of receiver and method, compared by identity, since a mod's equals may be its own.
    private static boolean running(Object receiver, Method method) {
        List<Object> running = RUNNING.get();
        for (int i = 0; i < running.size(); i += 2) {
            if (running.get(i) == receiver && running.get(i + 1).equals(method)) return true;
        }
        return false;
    }

    private static void broke(Broken key, Throwable failure) {
        BROKEN.add(key);
        Method method = key.method();
        String owner = method.getDeclaringClass().getName();
        LegacyLog.warnOnce("old override " + owner + "." + method.getName(), "The old " + method.getName() + " of " + owner
                + " failed, so for each item it fails on Retromod answers the way the host does by default until the game restarts: " + failure);
    }

    // The first stack's item among the arguments, or null when there is none.
    private static Object itemOf(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof ItemStack stack) return stack.getItem();
        }
        return null;
    }

    /** A lookup key: a hook name and the caller's constant parameter array, compared by identity. */
    private record Shape(String name, Class<?>[] params) {}

    /** A method that failed for an item; items live as long as the game, so holding them costs nothing. */
    private record Broken(Method method, Object item) {}

    private static Method search(Class<?> type, String name, Class<?>[] params) {
        Method[] methods;
        try {
            methods = type.getMethods();
        } catch (RuntimeException | LinkageError unreadable) {
            // A class whose other methods name a type the host lacks cannot list its methods.
            return null;
        }
        for (Method method : methods) {
            if (!method.getName().equals(name) || !fits(method.getParameterTypes(), params) || isHost(method.getDeclaringClass())) continue;
            try {
                // A mod's extension is often an anonymous class, whose public methods still need access to be called.
                method.setAccessible(true);
            } catch (RuntimeException closed) {
                return null;
            }
            return method;
        }
        return null;
    }

    private static boolean fits(Class<?>[] actual, Class<?>[] wanted) {
        if (actual.length != wanted.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (wanted[i] == null ? actual[i].isPrimitive() : actual[i] != wanted[i]) return false;
        }
        return true;
    }

    // The host's own declarations are its new hooks, never an old override.
    private static boolean isHost(Class<?> owner) {
        String name = owner.getName();
        return name.startsWith("net.minecraft.") || name.startsWith("net.neoforged.") || name.startsWith("net.minecraftforge.")
                || name.startsWith("com.mojang.") || name.startsWith("java.");
    }
}
