/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Renders every registration table, static mixin registries included, as sorted lines. */
final class RegistrationDigest {

    /** Fields that hold caches or per-transform state rather than registrations. */
    private static final Set<String> SKIPPED_FIELDS = Set.of(
            "interfaceKinds", "superclassVerdicts", "inheritedTargetSrgNames",
            "reportedIllegalSuperclasses", "registrationResetHooks", "targetSrgMethodIds",
            "targetSrgSamIndex", "methodRedirectOwners", "constructorRedirectOwners",
            "argDropOwners", "convertingOwners", "singletonOwners", "indyTypedOwners",
            "neutralizedMethodOwners");

    /** Classes whose static collections also decide what a transform does. */
    private static final List<String> STATIC_REGISTRIES = List.of(
            "com.retromod.mixin.MixinCompatibilityTransformer",
            "com.retromod.mixin.MixinTargetRedirector",
            "com.retromod.mixin.MixinHandlerResignature");

    private RegistrationDigest() {}

    static List<String> lines(RetromodTransformer transformer) {
        TreeSet<String> lines = new TreeSet<>();
        // Instance tables of the transformer.
        for (Field field : RetromodTransformer.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || SKIPPED_FIELDS.contains(field.getName())) {
                continue;
            }
            if (!isTable(field.getType())) continue;
            addTable(lines, "transformer." + field.getName(), read(field, transformer));
        }
        // Static registries outside the transformer, loaded without initializing them.
        for (String className : STATIC_REGISTRIES) {
            Class<?> registry;
            try {
                registry = Class.forName(className, false, RegistrationDigest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Field field : registry.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !isTable(field.getType())) continue;
                addTable(lines, registry.getSimpleName() + "." + field.getName(), read(field, null));
            }
        }
        return new ArrayList<>(lines);
    }

    static String sha256(List<String> lines) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean isTable(Class<?> type) {
        return Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
    }

    private static Object read(Field field, Object instance) {
        try {
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "<unreadable " + e.getClass().getSimpleName() + ">";
        }
    }

    private static void addTable(Set<String> lines, String table, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : List.copyOf(map.entrySet())) {
                lines.add(table + "\t" + stable(entry.getKey()) + "\t" + stable(entry.getValue()));
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object element : List.copyOf(collection)) {
                lines.add(table + "\t" + stable(element));
            }
        } else if (value != null) {
            lines.add(table + "\t" + stable(value));
        }
    }

    /** A rendering that never depends on identity hash codes or lambda class names. */
    static String stable(Object value) {
        if (value == null) return "null";
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?>) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return "sha256:" + sha256(List.of(HexFormat.of().formatHex(bytes)));
        }
        if (value instanceof Object[] array) {
            List<String> parts = new ArrayList<>();
            for (Object element : array) parts.add(stable(element));
            return parts.toString();
        }
        if (value instanceof Map<?, ?> map) {
            TreeSet<String> parts = new TreeSet<>();
            for (Map.Entry<?, ?> entry : List.copyOf(map.entrySet())) {
                parts.add(stable(entry.getKey()) + "=" + stable(entry.getValue()));
            }
            return parts.toString();
        }
        if (value instanceof Collection<?> collection) {
            TreeSet<String> parts = new TreeSet<>();
            for (Object element : List.copyOf(collection)) parts.add(stable(element));
            return parts.toString();
        }
        Class<?> type = value.getClass();
        if (type.isRecord()) {
            List<String> parts = new ArrayList<>();
            for (RecordComponent component : type.getRecordComponents()) {
                try {
                    component.getAccessor().setAccessible(true);
                    parts.add(stable(component.getAccessor().invoke(value)));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    parts.add("?");
                }
            }
            return type.getSimpleName() + parts;
        }
        // Functions and other objects have no stable text, so only their kind counts.
        return type.isSynthetic() || type.isHidden() ? "<function>" : "<" + type.getName() + ">";
    }
}
