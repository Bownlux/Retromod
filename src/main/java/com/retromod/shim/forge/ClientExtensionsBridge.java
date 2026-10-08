/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Routes Forge's {@code initializeClient(Consumer)} hooks to NeoForge's
 * {@code RegisterClientExtensionsEvent} (#306, #308).
 *
 * <p>On Forge an item, block or mob effect handed out its client extensions (custom renderer,
 * armor model, font) by overriding {@code initializeClient}, which Forge called while building the
 * object. NeoForge 21 removed that hook in favor of one registration event, so a migrated mod's
 * override is never called and its items render as plain items. This listener runs once on the
 * client, asks every registered object that still declares the old hook, and registers whatever
 * it hands back. Fluid types are left to NeoForge, which still calls their hook itself.
 */
public final class ClientExtensionsBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final String EVENT =
            "net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent";

    private ClientExtensionsBridge() {}

    /** Subscribe on Retromod's own mod bus; NeoForge posts the event to every mod's bus. */
    public static void listen() {
        // The event is client-only, and the dist cleaner refuses its class on a dedicated server.
        if (com.retromod.core.EnvironmentDetector.isDedicatedServer()) return;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> event = Class.forName(EVENT, false, loader);
            Class<?> loadingContext = Class.forName("net.neoforged.fml.ModLoadingContext", false, loader);
            Object context = loadingContext.getMethod("get").invoke(null);
            Object container = loadingContext.getMethod("getActiveContainer").invoke(context);
            Object bus = container.getClass().getMethod("getEventBus").invoke(container);
            // NeoForge posts mod-bus events one priority phase at a time across all mods, so the
            // lowest phase runs after every mod's own registrations. Those are then skipped
            // instead of registered twice, which NeoForge rejects.
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object lowest = Enum.valueOf((Class) Class.forName("net.neoforged.bus.api.EventPriority", false, loader),
                    "LOWEST");
            Method addListener = Class.forName("net.neoforged.bus.api.IEventBus", false, loader)
                    .getMethod("addListener", lowest.getClass(), Class.class, Consumer.class);
            Consumer<Object> listener = ClientExtensionsBridge::registerLegacyExtensions;
            addListener.invoke(bus, lowest, event, listener);
        } catch (ClassNotFoundException e) {
            LOGGER.debug("No RegisterClientExtensionsEvent on this host; Forge client hooks need no routing");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Retromod could not listen for NeoForge client extensions, so Forge items "
                    + "with custom renderers or armor models will render as plain items: {}", e.toString());
        }
    }

    static void registerLegacyExtensions(Object event) {
        int routed = 0;
        routed += routeRegistry(event, "ITEM", "registerItem");
        routed += routeRegistry(event, "BLOCK", "registerBlock");
        routed += routeRegistry(event, "MOB_EFFECT", "registerMobEffect");
        if (routed > 0) {
            LOGGER.info("Registered {} Forge client extension(s) through NeoForge", routed);
        }
    }

    private static int routeRegistry(Object event, String registryField, String registerMethod) {
        Iterable<?> entries;
        Method register;
        try {
            ClassLoader loader = event.getClass().getClassLoader();
            Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, loader);
            entries = (Iterable<?>) registries.getField(registryField).get(null);
            register = findArrayRegister(event.getClass(), registerMethod);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("Could not read {} for Forge client extensions: {}", registryField, e.toString());
            return 0;
        }
        if (register == null) return 0;
        int routed = 0;
        for (Object entry : entries) {
            if (routeOne(event, register, entry)) routed++;
        }
        return routed;
    }

    /** The {@code registerX(extensions, X...)} overload, not the {@code Holder<X>...} one. */
    static Method findArrayRegister(Class<?> eventType, String name) {
        for (Method method : eventType.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 2) continue;
            Class<?> elements = method.getParameterTypes()[1].getComponentType();
            if (elements != null && !elements.getName().equals("net.minecraft.core.Holder")) return method;
        }
        return null;
    }

    static boolean routeOne(Object event, Method register, Object entry) {
        Method hook = legacyHook(entry.getClass());
        if (hook == null || alreadyRegistered(event, register, entry)) return false;
        Object[] targets = (Object[]) Array.newInstance(register.getParameterTypes()[1].getComponentType(), 1);
        targets[0] = entry;
        boolean[] registered = {false};
        Consumer<Object> sink = extensions -> {
            try {
                register.invoke(event, extensions, targets);
                registered[0] = true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("Could not register Forge client extensions for {}: {}", entry, rootCause(e));
            }
        };
        try {
            hook.invoke(entry, sink);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Forge client extensions for {} failed to initialize: {}", entry, rootCause(e));
        }
        return registered[0];
    }

    /**
     * Whether the object already has extensions, through {@code isItemRegistered} and its siblings.
     * A mod that kept its Forge hook but also registers through the event must not be registered
     * a second time.
     */
    static boolean alreadyRegistered(Object event, Method register, Object entry) {
        String check = "is" + register.getName().substring("register".length()) + "Registered";
        for (Method method : event.getClass().getMethods()) {
            if (method.getName().equals(check) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(entry)) {
                try {
                    return Boolean.TRUE.equals(method.invoke(event, entry));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /** A public {@code initializeClient(Consumer)} declared by a mod class, not by Minecraft. */
    static Method legacyHook(Class<?> type) {
        try {
            Method hook = type.getMethod("initializeClient", Consumer.class);
            String owner = hook.getDeclaringClass().getName();
            if (owner.startsWith("net.minecraft.") || owner.startsWith("net.neoforged.")) return null;
            try {
                hook.setAccessible(true);
            } catch (RuntimeException ignored) {
                // An anonymous item class is not public; automatic mod modules still open it.
            }
            return hook;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.toString();
    }
}
