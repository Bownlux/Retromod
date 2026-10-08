/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;

/**
 * Forge's {@code Event.Result} on NeoForge, embedded per mod.
 *
 * <p>Forge gave every event one {@code DENY}, {@code DEFAULT}, {@code ALLOW} result. NeoForge
 * removed it and gave each event its own type instead: {@code MobSpawnEvent.PositionCheck.Result}
 * uses {@code SUCCEED} and {@code FAIL}, and the interaction events use {@code TriState}. A mod's
 * {@code setResult} or {@code setUseBlock} call is sent here with the method name, and the value
 * is converted to whatever enum that event's method takes, by constant name. A call the event has
 * no counterpart for does nothing, and a read of one answers {@code DEFAULT}.
 *
 * <p>This class must stay a single class file, since the embedder copies it alone: no switch over
 * an enum and no lambdas.
 */
public enum LegacyEventResult {
    DENY, DEFAULT, ALLOW;

    private static final String[] ALLOW_NAMES = {"ALLOW", "SUCCEED", "TRUE"};
    private static final String[] DENY_NAMES = {"DENY", "FAIL", "FALSE"};

    /** Forge's {@code event.<setter>(result)}. */
    public static void apply(Object event, LegacyEventResult result, String setter) {
        Method method = find(event.getClass(), setter, 1);
        if (method == null || !method.getParameterTypes()[0].isEnum()) return;
        Object converted = convert(method.getParameterTypes()[0], result == null ? DEFAULT : result);
        if (converted != null) invoke(method, event, converted);
    }

    /** Forge's {@code event.<getter>()}. */
    public static LegacyEventResult read(Object event, String getter) {
        Method method = find(event.getClass(), getter, 0);
        if (method == null || !method.getReturnType().isEnum()) return DEFAULT;
        Object value = invoke(method, event);
        return value == null ? DEFAULT : of(((Enum<?>) value).name());
    }

    /** Forge's {@code event.hasResult()}: true where the event still carries a result. */
    public static boolean hasResult(Object event) {
        Method method = find(event.getClass(), "getResult", 0);
        return method != null && method.getReturnType().isEnum();
    }

    static LegacyEventResult of(String name) {
        if (contains(ALLOW_NAMES, name)) return ALLOW;
        if (contains(DENY_NAMES, name)) return DENY;
        return DEFAULT;
    }

    static Object convert(Class<?> type, LegacyEventResult result) {
        String[] names = result == ALLOW ? ALLOW_NAMES : result == DENY ? DENY_NAMES : new String[]{"DEFAULT"};
        for (Object constant : type.getEnumConstants()) {
            if (contains(names, ((Enum<?>) constant).name())) return constant;
        }
        return null;
    }

    private static boolean contains(String[] names, String name) {
        for (String candidate : names) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }

    private static Method find(Class<?> type, String name, int parameters) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        }
        return null;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not call " + method + " on " + target, e);
        }
    }
}
