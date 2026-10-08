/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge's event-cancellation calls on NeoForge, embedded per mod.
 *
 * <p>Forge put {@code isCancelable}, {@code isCanceled} and {@code setCanceled} on every event and
 * returned the cancel flag from {@code bus.post}. NeoForge moved cancellation to the
 * {@code ICancellableEvent} interface and returns the event from {@code post}, so these calls fail
 * with {@code NoSuchMethodError} on any event that is not cancellable, and on {@code post}
 * everywhere. Each helper answers the way Forge did for a cancellable event and treats any other
 * event as never cancelled, which is what Forge's own non-cancelable events reported.
 */
public final class LegacyEventCalls {

    private static final String CANCELLABLE = "net.neoforged.bus.api.ICancellableEvent";
    private static final Map<Class<?>, Method> POST = new ConcurrentHashMap<>();
    /**
     * The adapter also rewrites cancel calls a NeoForge mod makes on cancellable events, which run
     * on hot paths such as ticks and rendering, so the hierarchy walk is done once per class. A
     * plain map rather than a {@code ClassValue}: the embedder copies this one class file, so it
     * must not need an inner class.
     */
    private static final Map<Class<?>, Boolean> CANCELLABLE_TYPES = new ConcurrentHashMap<>();

    private LegacyEventCalls() {}

    /** Forge's {@code boolean post(Event)}: post, then report whether the event was cancelled. */
    public static boolean post(Object bus, Object event) {
        Method post = POST.computeIfAbsent(bus.getClass(), LegacyEventCalls::findPost);
        try {
            post.invoke(bus, event);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not post " + event, e);
        }
        return isCanceled(event);
    }

    /**
     * Forge's {@code register(target)} with NeoForge's stricter rules relaxed to Forge's.
     *
     * <p>Forge skipped static handlers on an instance, accepted a class or instance with no
     * handlers, let a handler listen to an abstract event, and let the game bus hold mod-bus
     * handlers it never fired. NeoForge rejects the whole target for any of these. When it does,
     * the handlers Forge would have used are added one by one, and a handler NeoForge cannot take
     * on this bus is skipped with a warning, since it would never have run there.
     */
    public static void register(Object bus, Object target) {
        try {
            invoke(bus, findOne(bus.getClass(), "register", 1), target);
            return;
        } catch (IllegalArgumentException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("has no @SubscribeEvent methods")) return;
            if (!message.contains("NOT be static") && !message.contains("to be static")
                    && !message.contains("abstract") && !message.contains("not valid for this bus")) {
                throw e;
            }
        }
        // NeoForge registers handlers one at a time and throws at the first it rejects, so the
        // handlers before it are already live. Drop them, or each would fire twice.
        invoke(bus, findOne(bus.getClass(), "unregister", 1), target);
        Method addListener = null;
        for (Method method : bus.getClass().getMethods()) {
            if (method.getName().equals("addListener") && method.getParameterCount() == 4
                    && method.getParameterTypes()[2] == Class.class) {
                addListener = method;
            }
        }
        if (addListener == null) throw new IllegalStateException("No addListener(priority, boolean, Class, Consumer) on " + bus);
        boolean statics = target instanceof Class<?>;
        Class<?> type = statics ? (Class<?>) target : target.getClass();
        Object receiver = statics ? null : target;
        for (Method handler : type.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(handler.getModifiers()) != statics
                    || handler.getParameterCount() != 1) {
                continue;
            }
            java.lang.annotation.Annotation subscribe = subscribeAnnotation(handler);
            if (subscribe == null) continue;
            Class<?> eventType = handler.getParameterTypes()[0];
            if (java.lang.reflect.Modifier.isAbstract(eventType.getModifiers())) {
                System.getLogger("Retromod").log(System.Logger.Level.WARNING,
                        "Skipping " + handler + ": NeoForge never posts the abstract " + eventType.getName()
                        + " that this Forge listener expects");
                continue;
            }
            Object priority = annotationValue(subscribe, "priority");
            Object receiveCanceled = annotationValue(subscribe, "receiveCanceled");
            java.util.function.Consumer<Object> listener = event -> invoke(receiver, handler, event);
            try {
                invoke(bus, addListener, priority, receiveCanceled, eventType, listener);
            } catch (IllegalArgumentException wrongBus) {
                // Forge's game bus accepted mod-bus events and never fired them; NeoForge refuses.
                System.getLogger("Retromod").log(System.Logger.Level.WARNING,
                        "Skipping " + handler + " on this bus: " + wrongBus.getMessage());
            }
        }
    }

    private static java.lang.annotation.Annotation subscribeAnnotation(Method handler) {
        for (java.lang.annotation.Annotation annotation : handler.getAnnotations()) {
            if (annotation.annotationType().getName().equals("net.neoforged.bus.api.SubscribeEvent")) return annotation;
        }
        return null;
    }

    private static Object annotationValue(java.lang.annotation.Annotation annotation, String name) {
        try {
            return annotation.annotationType().getMethod(name).invoke(annotation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read " + name + " from " + annotation, e);
        }
    }

    private static Method findOne(Class<?> type, String name, int parameters) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters
                    && method.getParameterTypes()[0] == Object.class) {
                return method;
            }
        }
        throw new IllegalStateException("No " + name + " on " + type.getName());
    }

    private static Object invoke(Object target, Method method, Object... args) {
        try {
            method.setAccessible(true);
        } catch (RuntimeException ignored) {
            // Public members of an exported package stay callable.
        }
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not call " + method, e);
        }
    }

    /** Architectury 9's {@code EventBuses.registerModEventBus}: NeoForge already knows the bus. */
    public static void ignoreModEventBus(String modId, Object bus) {
        // Architectury 13 resolves a mod's bus from its container, so there is nothing to record.
    }

    public static boolean isCancelable(Object event) {
        return CANCELLABLE_TYPES.computeIfAbsent(event.getClass(), LegacyEventCalls::implementsCancellable);
    }

    public static boolean isCanceled(Object event) {
        if (!isCancelable(event)) return false;
        return (Boolean) call(event, "isCanceled", new Class<?>[0]);
    }

    public static void setCanceled(Object event, boolean canceled) {
        // Forge threw for a non-cancelable event; mods only call this where Forge allowed it,
        // so an event NeoForge made uncancellable is left to run.
        if (isCancelable(event)) {
            call(event, "setCanceled", new Class<?>[]{boolean.class}, canceled);
        } else if (canceled) {
            preventDamage(event);
        }
    }

    /**
     * Forge's cancellable {@code LivingDamageEvent} became NeoForge's {@code LivingDamageEvent.Pre},
     * which cannot be cancelled. Cancelling it meant no damage, so the damage is set to zero.
     */
    private static void preventDamage(Object event) {
        try {
            event.getClass().getMethod("setNewDamage", float.class).invoke(event, 0.0f);
        } catch (NoSuchMethodException notADamageEvent) {
            // Any other uncancellable event runs on, as Forge's non-cancelable events did.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not cancel the damage in " + event, e);
        }
    }

    private static boolean implementsCancellable(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                if (iface.getName().equals(CANCELLABLE) || implementsCancellable(iface)) return true;
            }
        }
        return false;
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = Class.forName(CANCELLABLE, false, target.getClass().getClassLoader())
                    .getMethod(name, types);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not call " + name + " on " + target, e);
        }
    }

    private static Method findPost(Class<?> busType) {
        for (Method method : busType.getMethods()) {
            if (method.getName().equals("post") && method.getParameterCount() == 1) return method;
        }
        throw new IllegalStateException("No post(Event) on " + busType.getName());
    }
}
