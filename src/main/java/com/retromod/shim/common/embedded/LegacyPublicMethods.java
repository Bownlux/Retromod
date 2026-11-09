/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Looks up methods through a public class or interface, so they can be invoked on an object whose own class is not public. */
public final class LegacyPublicMethods {

    private static final Class<?>[] NO_PARAMETERS = new Class<?>[0];
    // Per runtime class, so each class's hierarchy is searched once.
    private static final Map<Class<?>, Map<List<Object>, Method>> BY_SIGNATURE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method[]> ALL = new ConcurrentHashMap<>();

    private LegacyPublicMethods() {}

    /** {@link Class#getMethod}, answered by the most specific public type that declares the method. */
    public static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        Class<?>[] types = parameters == null ? NO_PARAMETERS : parameters.clone();
        Map<List<Object>, Method> known = BY_SIGNATURE.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>());
        // A null parameter type is kept as given, so getMethod below answers NoSuchMethodException as it always did.
        List<Object> key = Arrays.asList(name, Arrays.asList(types));
        Method found = known.get(key);
        if (found == null) {
            found = invocable(type, type.getMethod(name, types));
            known.put(key, found);
        }
        return found;
    }

    /** {@link Class#getMethods}, with each method answered the same way. */
    public static Method[] methods(Class<?> type) {
        return ALL.computeIfAbsent(type, LegacyPublicMethods::invocableMethods).clone();
    }

    private static Method[] invocableMethods(Class<?> type) {
        // Methods that resolve to the same public declaration are listed once.
        Set<Method> methods = new LinkedHashSet<>();
        for (Method method : type.getMethods()) methods.add(invocable(type, method));
        return methods.toArray(new Method[0]);
    }

    /** A public method from a public supertype of {@code type} when its own class is out of reach; a method that is not public comes back unchanged. */
    public static Method invocable(Class<?> type, Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || reachable(method.getDeclaringClass())) return method;
        // Invoking a supertype's declaration still runs the object's own override.
        if (!Modifier.isStatic(method.getModifiers())) {
            Method declared = publicDeclaration(type, method);
            if (declared != null) return declared;
        }
        // Nothing public declares it, as for a mod's own private class: the module may still let this copy open it.
        method.trySetAccessible();
        return method;
    }

    /** Superclasses nearest first, then every interface breadth first, so the most specific declaration wins. */
    private static Method publicDeclaration(Class<?> type, Method method) {
        ArrayDeque<Class<?>> interfaces = new ArrayDeque<>();
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            Method declared = publicInstanceMethod(owner, type, method);
            if (declared != null) return declared;
            interfaces.addAll(Arrays.asList(owner.getInterfaces()));
        }
        Set<Class<?>> seen = new HashSet<>();
        while (!interfaces.isEmpty()) {
            Class<?> owner = interfaces.poll();
            if (!seen.add(owner)) continue;
            Method declared = publicInstanceMethod(owner, type, method);
            if (declared != null) return declared;
            interfaces.addAll(Arrays.asList(owner.getInterfaces()));
        }
        return null;
    }

    private static Method publicInstanceMethod(Class<?> owner, Class<?> type, Method method) {
        if (!reachable(owner)) return null;
        try {
            // The exact descriptor first: getDeclaredMethod answers only the narrowest return type, which a bridge does not have.
            Method declared = sameDescriptor(owner, method);
            if (declared == null) declared = owner.getDeclaredMethod(method.getName(), method.getParameterTypes());
            int modifiers = declared.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) return null;
            return implementsDescriptor(type, method, declared) ? declared : null;
        } catch (NoSuchMethodException | SecurityException | LinkageError unreadable) {
            return null;
        }
    }

    private static Method sameDescriptor(Class<?> owner, Method method) {
        for (Method declared : owner.getDeclaredMethods()) {
            if (declared.getReturnType() == method.getReturnType() && declared.getName().equals(method.getName())
                    && Arrays.equals(declared.getParameterTypes(), method.getParameterTypes())) {
                return declared;
            }
        }
        return null;
    }

    /** Whether a call to {@code declared} reaches {@code method} on a {@code type} object. */
    private static boolean implementsDescriptor(Class<?> type, Method method, Method declared) {
        if (declared.getReturnType() == method.getReturnType()) return true;
        // Otherwise compare the code each descriptor runs on this class. An old mod's class can implement a
        // since-changed interface whose method kept the name and parameters but not the return type; it has
        // no code for that descriptor, so the declaration would run something else or nothing.
        Method[] all = type.getMethods();
        Method runs = runs(all, method, method);
        return runs != null && runs.equals(runs(all, method, implementation(all, declared)));
    }

    /** The concrete method {@code type} answers for {@code declared}'s exact descriptor, or null. */
    private static Method implementation(Method[] all, Method declared) {
        for (Method candidate : all) {
            if (!Modifier.isAbstract(candidate.getModifiers()) && sameShape(candidate, declared)
                    && candidate.getReturnType() == declared.getReturnType()) {
                return candidate;
            }
        }
        return null;
    }

    /** The method {@code chosen} runs: itself, or for a javac bridge the one concrete method of that name and parameters. */
    private static Method runs(Method[] all, Method method, Method chosen) {
        if (chosen == null || !chosen.isBridge()) return chosen;
        Method target = null;
        for (Method candidate : all) {
            if (candidate.isBridge() || Modifier.isAbstract(candidate.getModifiers()) || !sameShape(candidate, method)) continue;
            // Two concrete methods of one shape are not javac's doing, so the bridge's target is unknown.
            if (target != null) return null;
            target = candidate;
        }
        return target;
    }

    private static boolean sameShape(Method a, Method b) {
        return a.getName().equals(b.getName()) && Arrays.equals(a.getParameterTypes(), b.getParameterTypes());
    }

    /** Whether reflection from this copy may call a public member of {@code type}. */
    private static boolean reachable(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) return false;
        // A public class in a package its module does not export is out of reach as well.
        return type.getModule().isExported(type.getPackageName(), LegacyPublicMethods.class.getModule());
    }
}
