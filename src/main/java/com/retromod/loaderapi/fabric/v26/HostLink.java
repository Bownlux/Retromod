/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.ClassResourceInspector;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Reaches host Minecraft and Fabric API members by 26.x name through cached handles, never initializing a class. */
public final class HostLink {

    /**
     * A member to look up: owner and name in internal form, descriptor in JVM form. A descriptor
     * without parentheses names a field.
     */
    public record Member(String owner, String name, String desc, boolean isStatic) {

        public static Member virtual(String owner, String name, String desc) {
            return new Member(owner, name, desc, false);
        }

        public static Member staticMethod(String owner, String name, String desc) {
            return new Member(owner, name, desc, true);
        }

        /** A constructor; the handle returns the new object. */
        public static Member constructor(String owner, String desc) {
            return new Member(owner, "<init>", desc, false);
        }
    }

    private static final Map<Member, Optional<MethodHandle>> HANDLES = new ConcurrentHashMap<>();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

    private HostLink() {}

    /** Whether the host has {@code internalName}, read from its class resource without loading it. */
    public static boolean exists(String internalName) {
        return ClassResourceInspector.exists(internalName);
    }

    /** The class for {@code internalName} without initializing it, or empty when the host lacks it. */
    public static Optional<Class<?>> type(String internalName) {
        try {
            return Optional.of(Class.forName(internalName.replace('/', '.'), false, loader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** A handle for {@code member}, resolved once, or empty when the host lacks it. */
    public static Optional<MethodHandle> handle(Member member) {
        return HANDLES.computeIfAbsent(member, HostLink::resolve);
    }

    /** The first of {@code candidates} the host has, for members that moved between 26.x hosts. */
    public static Optional<MethodHandle> firstPresent(Member... candidates) {
        for (Member candidate : candidates) {
            Optional<MethodHandle> found = handle(candidate);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /** The handle for {@code member}, or an error that names the member and the host gap. */
    public static MethodHandle require(Member member) {
        return handle(member).orElseThrow(() -> new IllegalStateException(
                "This Minecraft host has no " + member.owner() + "." + member.name()
                        + member.desc() + ". The loader API runtime cannot bridge this call here."));
    }

    private static Optional<MethodHandle> resolve(Member member) {
        Optional<Class<?>> owner = type(member.owner());
        if (owner.isEmpty()) return Optional.empty();
        try {
            // A descriptor without parentheses names a field, read through a getter handle.
            if (!member.desc().startsWith("(")) {
                Class<?> fieldType = MethodType.fromMethodDescriptorString(
                        "()" + member.desc(), loader()).returnType();
                return Optional.of(member.isStatic()
                        ? LOOKUP.findStaticGetter(owner.get(), member.name(), fieldType)
                        : LOOKUP.findGetter(owner.get(), member.name(), fieldType));
            }
            MethodType type = MethodType.fromMethodDescriptorString(member.desc(), loader());
            if ("<init>".equals(member.name())) {
                return Optional.of(LOOKUP.findConstructor(owner.get(), type));
            }
            MethodHandle handle = member.isStatic()
                    ? LOOKUP.findStatic(owner.get(), member.name(), type)
                    : LOOKUP.findVirtual(owner.get(), member.name(), type);
            return Optional.of(handle);
        } catch (ReflectiveOperationException | TypeNotPresentException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static ClassLoader loader() {
        ClassLoader loader = HostLink.class.getClassLoader();
        return loader != null ? loader : ClassLoader.getSystemClassLoader();
    }

    /** Forgets every resolved handle, for tests that swap the classes they look up. */
    static void clearForTesting() {
        HANDLES.clear();
    }
}
