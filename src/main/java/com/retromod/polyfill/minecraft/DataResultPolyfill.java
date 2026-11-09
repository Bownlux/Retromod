/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Polyfill for DataFixerUpper API changes in DFU 9.x (MC 26.1): DataResult went from a
 * class to an interface and some methods were removed/renamed. DFU classes aren't on the
 * compile classpath, so this works through reflection.
 */
package com.retromod.polyfill.minecraft;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Polyfill methods for DataResult API changes.
 *
 * Old: DataResult.get() returns Either of (R, DataResult.PartialResult of R).
 * New: result() returns Optional of R, error() returns Optional of Error of R.
 * Wired in via a devirtualized redirect: DataResult.get() to DataResultPolyfill.get(DataResult).
 */
public class DataResultPolyfill {

    private static final String DATA_RESULT = "com.mojang.serialization.DataResult";

    private static Method eitherLeftMethod;
    private static Method eitherRightMethod;

    static {
        try {
            Class<?> eitherClass = Class.forName("com.mojang.datafixers.util.Either");
            eitherLeftMethod = eitherClass.getMethod("left", Object.class);
            eitherRightMethod = eitherClass.getMethod("right", Object.class);
        } catch (Exception e) {
            // fails later only if get() is ever called
        }
    }

    /**
     * Polyfill for the removed DataResult.get(): Left holds the success value, Right an error wrapper.
     */
    @SuppressWarnings("unchecked")
    public static Object get(Object dataResult) {
        try {
            Method resultMethod = LegacyPublicMethods.method(dataResult.getClass(), "result");
            Optional<?> result = (Optional<?>) resultMethod.invoke(dataResult);

            if (result.isPresent()) {
                return eitherLeftMethod.invoke(null, result.get());
            }

            Method errorMethod = LegacyPublicMethods.method(dataResult.getClass(), "error");
            Optional<?> error = (Optional<?>) errorMethod.invoke(dataResult);

            if (error.isPresent()) {
                return eitherRightMethod.invoke(null, error.get());
            }

            // neither present: fall back to left(null)
            return eitherLeftMethod.invoke(null, (Object) null);
        } catch (Exception e) {
            throw new RuntimeException("DataResult.get() polyfill failed", e);
        }
    }

    /** DataFixerUpper 6's {@code getOrThrow(allowPartial, onError)}, which 1.20.5 removed. */
    public static Object getOrThrow(Object dataResult, boolean allowPartial, Consumer<String> onError) {
        String[] message = new String[1];
        try {
            Method[] calls = GET_OR_THROW_CALLS.get(dataResult.getClass());
            // A success returns its value, null included; the function would only build an error.
            if ((Boolean) calls[0].invoke(dataResult)) {
                Function<String, RuntimeException> neverCalled = RuntimeException::new;
                return calls[1].invoke(dataResult, neverCalled);
            }
            // An error builds its message once and hands it to onError, then to the exception.
            Consumer<String> report = text -> {
                message[0] = text;
                onError.accept(text);
            };
            Optional<?> partial = (Optional<?>) calls[2].invoke(dataResult, report);
            if (allowPartial && partial.isPresent()) return partial.get();
        } catch (InvocationTargetException thrown) {
            // onError may throw on purpose; that reaches the caller unwrapped, as it did in DFU 6.
            Throwable cause = thrown.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("DataResult.getOrThrow failed", cause);
        } catch (ReflectiveOperationException | IllegalStateException missing) {
            throw new IllegalStateException("Retromod could not call " + dataResult.getClass().getName()
                    + " for the old getOrThrow(boolean, Consumer)", missing);
        }
        throw new RuntimeException(message[0]);
    }

    /** isSuccess, getOrThrow(Function) and resultOrPartial(Consumer) of a result class, looked up once per class. */
    private static final ClassValue<Method[]> GET_OR_THROW_CALLS = new ClassValue<>() {
        @Override
        protected Method[] computeValue(Class<?> type) {
            Class<?> api = dataResultInterface(type);
            try {
                return new Method[]{api.getMethod("isSuccess"), api.getMethod("getOrThrow", Function.class),
                        api.getMethod("resultOrPartial", Consumer.class)};
            } catch (NoSuchMethodException missing) {
                throw new IllegalStateException(api.getName() + " has no " + missing.getMessage(), missing);
            }
        }
    };

    /** The public DataResult interface a result implements, so its methods resolve reflectively. */
    private static Class<?> dataResultInterface(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                if (iface.getName().equals(DATA_RESULT)) return iface;
            }
        }
        return type;
    }
}
