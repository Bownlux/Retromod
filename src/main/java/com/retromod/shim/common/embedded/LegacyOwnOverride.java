/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

/** Finds a mod class's own copy of a method the host removed, so a redirected call still runs it. */
public final class LegacyOwnOverride extends ClassValue<Optional<MethodHandle>> {

    // The removed method, such as the five-argument Mob.finalizeSpawn, and the type of the helper
    // Retromod sends calls to it to: the receiver, then the removed method's parameters.
    private final String name;
    private final MethodType callType;

    public LegacyOwnOverride(String name, MethodType callType) {
        this.name = name;
        this.callType = callType;
    }

    /** The receiver's own method, typed as the helper's call, or null when it declares none. */
    public MethodHandle find(Object receiver) {
        // On the mod's own version the call was virtual: a mob that declared the old method ran its
        // own copy, even when the call named Mob. So the helper asks here before it calls the host.
        return receiver == null ? null : get(receiver.getClass()).orElse(null);
    }

    /** Looks up one class; ClassValue keeps the answer, so each class is looked up once. */
    @Override
    protected Optional<MethodHandle> computeValue(Class<?> type) {
        Class<?>[] parameters = callType.dropParameterTypes(0, 1).parameterArray();
        // The receiver's class, or its nearest superclass outside Minecraft, that declares the old
        // name and parameter types answers. Minecraft's classes are skipped: the host lost the method.
        for (Class<?> owner = type; owner != null && !isMinecraft(owner); owner = owner.getSuperclass()) {
            Method declared;
            try {
                declared = owner.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException e) {
                continue;
            } catch (LinkageError | RuntimeException e) {
                // The class's methods cannot be listed, so its declarations are unknown.
                return Optional.empty();
            }
            if (!takesPartInDispatch(declared)) continue;
            try {
                declared.setAccessible(true);
                return Optional.of(MethodHandles.lookup().unreflect(declared).asType(callType));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** An instance method a virtual call can reach, returning what the helper returns. */
    private boolean takesPartInDispatch(Method declared) {
        int modifiers = declared.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) return false;
        Class<?> expected = callType.returnType();
        Class<?> actual = declared.getReturnType();
        return expected.isPrimitive() ? expected == actual : expected.isAssignableFrom(actual);
    }

    // Checked by name, because this class is copied into each mod that needs it and has no
    // compile-time Minecraft dependency.
    private static boolean isMinecraft(Class<?> type) {
        return type.getName().startsWith("net.minecraft.");
    }
}
