/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns the plain {@code Map<Item, CauldronInteraction>} an old cauldron block passed to
 * {@code AbstractCauldronBlock} into the type its constructor takes on this host. See
 * {@link com.retromod.shim.common.LegacyBlockConstructorBridge}.
 *
 * <p>1.20.3 to 1.21.11 take a named {@code InteractionMap}. It is made with the host's own
 * {@code newInteractionMap}, whose map answers items the mod did not list with the vanilla pass
 * result, where the mod's plain map answered null. 26.1 and later take a {@code Dispatcher}, which
 * gets the mod's entries one by one.
 *
 * <p>The vanilla tables a mod adds to, such as {@code CauldronInteraction.EMPTY}, were plain maps
 * too. They are the host's interaction map's own map, or on 26.1 and later a map that writes
 * through to the matching {@code CauldronInteractions} dispatcher.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacyCauldronInteractions {

    private static final String INTERACTION = "net.minecraft.core.cauldron.CauldronInteraction";
    private static final String DISPATCHER = INTERACTION + "$Dispatcher";
    private static final String ITEM = "net.minecraft.world.item.Item";
    private static final String DISPATCHERS = "net.minecraft.core.cauldron.CauldronInteractions";
    private static final AtomicInteger NEXT_NAME = new AtomicInteger();

    private LegacyCauldronInteractions() {}

    public static Object interactions(Object entries) {
        Map<?, ?> legacy = (Map<?, ?>) entries;
        ClassLoader loader = LegacyCauldronInteractions.class.getClassLoader();
        try {
            Class<?> interaction = Class.forName(INTERACTION, true, loader);
            Method newMap = findStatic(interaction, "newInteractionMap");
            if (newMap != null) return namedMap(newMap, legacy);
            return dispatcher(Class.forName(DISPATCHER, true, loader), interaction,
                    Class.forName(ITEM, false, loader), legacy);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException("Retromod could not build the interactions of an old cauldron block", e);
        }
    }

    /** The old {@code CauldronInteraction.EMPTY}. */
    public static Map<?, ?> empty() {
        return vanillaTable("EMPTY");
    }

    /** The old {@code CauldronInteraction.WATER}. */
    public static Map<?, ?> water() {
        return vanillaTable("WATER");
    }

    /** The old {@code CauldronInteraction.LAVA}. */
    public static Map<?, ?> lava() {
        return vanillaTable("LAVA");
    }

    /** The old {@code CauldronInteraction.POWDER_SNOW}. */
    public static Map<?, ?> powderSnow() {
        return vanillaTable("POWDER_SNOW");
    }

    private static Map<?, ?> vanillaTable(String name) {
        ClassLoader loader = LegacyCauldronInteractions.class.getClassLoader();
        try {
            Class<?> interaction = Class.forName(INTERACTION, true, loader);
            Object table;
            try {
                table = interaction.getField(name).get(null);
            } catch (NoSuchFieldException movedIn26) {
                Object dispatcher = Class.forName(DISPATCHERS, true, loader).getField(name).get(null);
                return writeThrough(dispatcher, interaction, Class.forName(ITEM, false, loader));
            }
            return (Map<?, ?>) table.getClass().getMethod("map").invoke(table);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException("Retromod could not find the vanilla cauldron table " + name, e);
        }
    }

    /** A map whose {@code put} and {@code putAll} add to the dispatcher; it cannot be read. */
    private static Map<?, ?> writeThrough(Object dispatcher, Class<?> interaction, Class<?> item)
            throws ReflectiveOperationException {
        Method put = dispatcher.getClass().getDeclaredMethod("put", item, interaction);
        try {
            put.setAccessible(true);
        } catch (RuntimeException alreadyPublic) {
            // NeoForge and Forge make put public; plain Minecraft keeps it package-private.
        }
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "put" -> {
                    put.invoke(dispatcher, args[0], args[1]);
                    return null;
                }
                case "putAll" -> {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) args[0]).entrySet()) {
                        put.invoke(dispatcher, entry.getKey(), entry.getValue());
                    }
                    return null;
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "toString" -> {
                    return "cauldron interactions of " + dispatcher;
                }
                default -> throw new UnsupportedOperationException(
                        "Since 26.1 a vanilla cauldron table only takes new entries, not " + method.getName());
            }
        };
        return (Map<?, ?>) java.lang.reflect.Proxy.newProxyInstance(Map.class.getClassLoader(),
                new Class<?>[] {Map.class}, handler);
    }

    @SuppressWarnings("unchecked")
    private static Object namedMap(Method newMap, Map<?, ?> legacy) throws ReflectiveOperationException {
        // The name only labels the map for the block codec; each old cauldron gets its own.
        Object interactions = newMap.invoke(null, "retromod_legacy_cauldron_" + NEXT_NAME.incrementAndGet());
        Map<Object, Object> map = (Map<Object, Object>) interactions.getClass().getMethod("map").invoke(interactions);
        map.putAll(legacy);
        return interactions;
    }

    private static Object dispatcher(Class<?> dispatcherType, Class<?> interaction, Class<?> item,
            Map<?, ?> legacy) throws ReflectiveOperationException {
        Object dispatcher = dispatcherType.getConstructor().newInstance();
        Method put = dispatcherType.getDeclaredMethod("put", item, interaction);
        try {
            put.setAccessible(true);
        } catch (RuntimeException alreadyPublic) {
            // NeoForge and Forge make put public; plain Minecraft keeps it package-private.
        }
        for (Map.Entry<?, ?> entry : legacy.entrySet()) {
            put.invoke(dispatcher, entry.getKey(), entry.getValue());
        }
        return dispatcher;
    }

    private static Method findStatic(Class<?> owner, String name) {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }
}
