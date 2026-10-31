/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs Forge 1.19.4's {@code CreativeModeTabEvent.Register} calls on the {@code RegisterEvent} that replaced it. */
public final class LegacyCreativeTabEvents {

    // Forge 1.19.4 put a tab with no order of its own after the spawn eggs.
    private static final List<Object> DEFAULT_AFTER = List.of("minecraft:spawn_eggs");

    // RegisterEvent fires once per registry, and the mod's handler runs each time. Each tab is
    // built on the first call and registered only on the creative tab registry's turn.
    private static final Map<String, Object> TABS = new ConcurrentHashMap<>();
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private LegacyCreativeTabEvents() {}

    /**
     * {@code event.registerCreativeModeTab(name, before, after, configurator)}. {@code calls} is the
     * generated class that makes the Minecraft calls, which carry the host's member names.
     */
    public static Object register(Class<?> calls, Object event, Object name, List<?> before, List<?> after,
            Consumer<Object> configurator) {
        try {
            String key = String.valueOf(name);
            Object tab = TABS.get(key);
            if (tab == null) {
                Object builder = calls.getMethod("builder").invoke(null);
                configurator.accept(builder);
                // Forge 1.20 names each list by where the other tabs go: withTabsBefore lists the
                // tabs before this one, which 1.19.4 called this tab's afterEntries.
                order(calls, builder, "withTabsBefore", after == null ? DEFAULT_AFTER : after);
                order(calls, builder, "withTabsAfter", before == null ? List.of() : before);
                tab = staticCall(calls, "build", builder);
                TABS.put(key, tab);
            }
            Object registry = event.getClass().getMethod("getRegistryKey").invoke(event);
            if (Boolean.TRUE.equals(staticCall(calls, "isTabRegistry", registry)) && REGISTERED.add(key)) {
                Object registered = tab;
                registerMethod(event).invoke(event, registry, name, (Supplier<Object>) () -> registered);
            }
            return tab;
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not register the creative tab " + name, e);
        }
    }

    /** {@code builder.withTabsBefore(ids)} or {@code withTabsAfter(ids)}, from tabs, ids, or id text. */
    private static void order(Class<?> calls, Object builder, String method, List<?> entries)
            throws ReflectiveOperationException {
        Method toId = calls.getMethod("id", Object.class);
        List<Object> ids = new ArrayList<>();
        for (Object entry : entries) {
            Object id = toId.invoke(null, entry);
            // A modded tab that is not registered yet has no id to order against.
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) return;
        Object array = Array.newInstance(toId.getReturnType(), ids.size());
        for (int i = 0; i < ids.size(); i++) Array.set(array, i, ids.get(i));
        builder.getClass().getMethod(method, array.getClass()).invoke(builder, array);
    }

    /** {@code RegisterEvent.register(registryKey, id, supplier)}. */
    private static Method registerMethod(Object event) throws NoSuchMethodException {
        for (Method method : event.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("register") && parameters.length == 3 && parameters[2] == Supplier.class) {
                return method;
            }
        }
        throw new NoSuchMethodException(event.getClass().getName() + ".register(key, id, supplier)");
    }

    private static Object staticCall(Class<?> calls, String name, Object argument) throws ReflectiveOperationException {
        for (Method method : calls.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) return method.invoke(null, argument);
        }
        throw new NoSuchMethodException(calls.getName() + "." + name);
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }
}
