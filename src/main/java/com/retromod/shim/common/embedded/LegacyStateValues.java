/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Rebuilds the property map an old {@code StateHolder.getValues()} call returned. */
public final class LegacyStateValues {

    private static final Map<Class<?>, Method[]> ACCESSORS = new ConcurrentHashMap<>();

    private LegacyStateValues() {}

    /** The property-to-value map, in the stream's order, that the old method returned. */
    public static Map<Object, Object> fromValueStream(Stream<?> values) {
        // Up to 1.21.11 a block or fluid state handed out a map from each Property to its value. 26.1
        // returns a stream of Property.Value records instead, which LegacyStateValuesAdapter passes
        // here. Mods that copy a state's properties onto another block, as every MCreator "replace
        // block" procedure does, read that map and failed with NoSuchMethodError.
        Map<Object, Object> map = new LinkedHashMap<>();
        for (Iterator<?> it = values.iterator(); it.hasNext(); ) {
            Object entry = it.next();
            Method[] accessors = accessors(entry.getClass());
            map.put(read(accessors[0], entry), read(accessors[1], entry));
        }
        return Collections.unmodifiableMap(map);
    }

    /** The public {@code property()} and {@code value()} accessors of one record type, cached. */
    private static Method[] accessors(Class<?> type) {
        // Found by name: this class is copied into each mod that needs it, so it has no compile-time
        // Minecraft dependency.
        Method[] known = ACCESSORS.get(type);
        if (known != null) return known;
        try {
            Method[] found = {type.getMethod("property"), type.getMethod("value")};
            ACCESSORS.put(type, found);
            return found;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Retromod cannot read the block state property entry "
                    + type.getName() + ", which has no property() and value() accessors", e);
        }
    }

    /** Calls one accessor, rethrowing what it throws. */
    private static Object read(Method accessor, Object entry) {
        try {
            return accessor.invoke(entry);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod cannot call " + accessor + " on a block state property", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }
}
