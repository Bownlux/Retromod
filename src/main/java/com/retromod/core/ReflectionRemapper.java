/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.retromod.util.RetromodLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime reflection remapper: when a mod reflects on a Minecraft member by an old name that no longer
 * exists, look the name up in the transformer's redirect tables and retry. Transformed mod code calls
 * {@code ReflectionRemapper.getMethod(clazz, "getWorld")} in place of {@code clazz.getMethod("getWorld")}.
 */
public final class ReflectionRemapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-reflection");

    private static final Map<MethodKey, Method> methodCache = new ConcurrentHashMap<>();
    private static final Map<FieldKey, Field> fieldCache = new ConcurrentHashMap<>();

    // className -> (oldName -> newName)
    private static final Map<String, Map<String, String>> methodRemaps = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> fieldRemaps = new ConcurrentHashMap<>();
    // oldClassName -> newClassName
    private static final Map<String, String> classRemaps = new ConcurrentHashMap<>();

    static {
        initializeRemappings();
    }

    private ReflectionRemapper() {
    }

    private static void initializeRemappings() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        classRemaps.putAll(transformer.getClassRedirects());

        for (var entry : transformer.getMethodRedirects().entrySet()) {
            var key = entry.getKey();
            var target = entry.getValue();

            String className = key.owner().replace('/', '.');
            String oldMethod = key.name();
            String newMethod = target.name();
            
            if (!oldMethod.equals(newMethod)) {
                methodRemaps
                    .computeIfAbsent(className, k -> new ConcurrentHashMap<>())
                    .put(oldMethod, newMethod);
            }
        }
        
        LOGGER.debug(RetromodLog.DETAIL, "ReflectionRemapper initialized with {} class remaps, {} method remap groups",
            classRemaps.size(), methodRemaps.size());
    }
    
    public static void addMethodRemap(String className, String oldName, String newName) {
        methodRemaps
            .computeIfAbsent(className, k -> new ConcurrentHashMap<>())
            .put(oldName, newName);
    }

    public static void addFieldRemap(String className, String oldName, String newName) {
        fieldRemaps
            .computeIfAbsent(className, k -> new ConcurrentHashMap<>())
            .put(oldName, newName);
    }

    /** Drop-in for {@code clazz.getMethod}, retrying under the remapped name. */
    public static Method getMethod(Class<?> clazz, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {

        MethodKey key = new MethodKey(clazz, name, parameterTypes);

        Method cached = methodCache.get(key);
        if (cached != null) return cached;

        try {
            Method method = clazz.getMethod(name, parameterTypes);
            methodCache.put(key, method);
            return method;
        } catch (NoSuchMethodException e) {
            // fall through to remap
        }

        String remappedName = remapMethodName(clazz, name);
        if (remappedName != null && !remappedName.equals(name)) {
            LOGGER.debug("Remapping reflection method: {}.{} -> {}",
                clazz.getName(), name, remappedName);

            Method method = clazz.getMethod(remappedName, parameterTypes);
            methodCache.put(key, method);
            return method;
        }

        Method method = findMethodInHierarchy(clazz, name, parameterTypes);
        if (method != null) {
            methodCache.put(key, method);
            return method;
        }

        throw new NoSuchMethodException(clazz.getName() + "." + name);
    }

    /** Drop-in for {@code clazz.getDeclaredMethod}, retrying under the remapped name. */
    public static Method getDeclaredMethod(Class<?> clazz, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {

        try {
            return clazz.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            // fall through to remap
        }

        String remappedName = remapMethodName(clazz, name);
        if (remappedName != null && !remappedName.equals(name)) {
            LOGGER.debug("Remapping reflection declared method: {}.{} -> {}",
                clazz.getName(), name, remappedName);
            return clazz.getDeclaredMethod(remappedName, parameterTypes);
        }

        throw new NoSuchMethodException(clazz.getName() + "." + name);
    }

    private static Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {}

            String remapped = remapMethodName(current, name);
            if (remapped != null && !remapped.equals(name)) {
                try {
                    return current.getDeclaredMethod(remapped, parameterTypes);
                } catch (NoSuchMethodException ignored) {}
            }

            current = current.getSuperclass();
        }

        return null;
    }

    /** Resolve a method's new name from this class, its superclasses, then its interfaces. */
    private static String remapMethodName(Class<?> clazz, String name) {
        Map<String, String> remaps = methodRemaps.get(clazz.getName());
        if (remaps != null) {
            String mapped = remaps.get(name);
            if (mapped != null) return mapped;
        }

        Class<?> superClass = clazz.getSuperclass();
        while (superClass != null && superClass != Object.class) {
            remaps = methodRemaps.get(superClass.getName());
            if (remaps != null) {
                String mapped = remaps.get(name);
                if (mapped != null) return mapped;
            }
            superClass = superClass.getSuperclass();
        }

        for (Class<?> iface : clazz.getInterfaces()) {
            remaps = methodRemaps.get(iface.getName());
            if (remaps != null) {
                String mapped = remaps.get(name);
                if (mapped != null) return mapped;
            }
        }

        return null;
    }

    /** Drop-in for {@code clazz.getField}, retrying under the remapped name. */
    public static Field getField(Class<?> clazz, String name) throws NoSuchFieldException {
        FieldKey key = new FieldKey(clazz, name);

        Field cached = fieldCache.get(key);
        if (cached != null) return cached;

        try {
            Field field = clazz.getField(name);
            fieldCache.put(key, field);
            return field;
        } catch (NoSuchFieldException e) {
            // fall through to remap
        }

        String remappedName = remapFieldName(clazz, name);
        if (remappedName != null && !remappedName.equals(name)) {
            LOGGER.debug("Remapping reflection field: {}.{} -> {}",
                clazz.getName(), name, remappedName);

            Field field = clazz.getField(remappedName);
            fieldCache.put(key, field);
            return field;
        }

        throw new NoSuchFieldException(clazz.getName() + "." + name);
    }

    /** Drop-in for {@code clazz.getDeclaredField}, retrying under the remapped name. */
    public static Field getDeclaredField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            // fall through to remap
        }

        String remappedName = remapFieldName(clazz, name);
        if (remappedName != null && !remappedName.equals(name)) {
            LOGGER.debug("Remapping reflection declared field: {}.{} -> {}",
                clazz.getName(), name, remappedName);
            return clazz.getDeclaredField(remappedName);
        }

        throw new NoSuchFieldException(clazz.getName() + "." + name);
    }

    private static String remapFieldName(Class<?> clazz, String name) {
        Map<String, String> remaps = fieldRemaps.get(clazz.getName());
        if (remaps != null) {
            String mapped = remaps.get(name);
            if (mapped != null) return mapped;
        }
        return null;
    }

    /** Drop-in for {@code Class.forName(name)}, retrying under the remapped class name. */
    public static Class<?> forName(String className) throws ClassNotFoundException {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            // fall through to remap
        }

        String internalName = className.replace('.', '/');
        String remappedInternal = classRemaps.get(internalName);

        if (remappedInternal != null) {
            String remappedName = remappedInternal.replace('/', '.');
            LOGGER.debug("Remapping reflection class: {} -> {}", className, remappedName);
            return Class.forName(remappedName);
        }

        throw new ClassNotFoundException(className);
    }

    /** Drop-in for {@code Class.forName(name, initialize, loader)}, retrying under the remapped class name. */
    public static Class<?> forName(String className, boolean initialize, ClassLoader loader)
            throws ClassNotFoundException {

        try {
            return Class.forName(className, initialize, loader);
        } catch (ClassNotFoundException e) {
            // fall through to remap
        }

        String internalName = className.replace('.', '/');
        String remappedInternal = classRemaps.get(internalName);

        if (remappedInternal != null) {
            String remappedName = remappedInternal.replace('/', '.');
            LOGGER.debug("Remapping reflection class: {} -> {}", className, remappedName);
            return Class.forName(remappedName, initialize, loader);
        }

        throw new ClassNotFoundException(className);
    }

    public static Object invoke(Object obj, String methodName, Object... args)
            throws ReflectiveOperationException {

        Class<?> clazz = obj.getClass();
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
        }

        Method method = getMethod(clazz, methodName, paramTypes);
        method.setAccessible(true);
        return method.invoke(obj, args);
    }

    public static Object getFieldValue(Object obj, String fieldName)
            throws ReflectiveOperationException {

        Field field = getField(obj.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(obj);
    }

    public static void setFieldValue(Object obj, String fieldName, Object value)
            throws ReflectiveOperationException {

        Field field = getField(obj.getClass(), fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    public static boolean hasMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            getMethod(clazz, name, parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public static boolean hasField(Class<?> clazz, String name) {
        try {
            getField(clazz, name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private record MethodKey(Class<?> clazz, String name, Class<?>[] parameterTypes) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MethodKey that)) return false;
            return clazz.equals(that.clazz) && 
                   name.equals(that.name) && 
                   Arrays.equals(parameterTypes, that.parameterTypes);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(clazz, name, Arrays.hashCode(parameterTypes));
        }
    }
    
    private record FieldKey(Class<?> clazz, String name) {}
}
