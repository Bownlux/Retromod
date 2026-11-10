/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Safety shims for MC 26.1+ APIs that old Fabric mods touch before they're ready, or that 26.1 removed.
 *
 * 26.1 binds item components during data pack loading, so Item.getDefaultInstance() earlier throws
 * NPE("Components not bound yet"). Mods that build ItemStacks in static init or early callbacks hit
 * this; we hand back ItemStack.EMPTY so they keep loading.
 */
public class ItemSafetyShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ItemSafety");
    private static volatile boolean warned = false;

    // Reflection: MC isn't on the compile classpath. Resolved lazily.
    private static volatile Method getDefaultInstanceMethod;
    private static volatile Object itemStackEmpty;
    private static volatile boolean emptyResolved = false;

    /** Item.getDefaultInstance() that returns ItemStack.EMPTY instead of throwing when components aren't bound yet. */
    public static Object safeGetDefaultInstance(Object item) {
        try {
            Method m = getDefaultInstanceMethod;
            if (m == null || m.getDeclaringClass() != item.getClass()) {
                m = LegacyPublicMethods.method(item.getClass(), "getDefaultInstance");
                getDefaultInstanceMethod = m;
            }
            return m.invoke(item);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof NullPointerException npe
                    && npe.getMessage() != null
                    && npe.getMessage().contains("Components not bound")) {
                if (!warned) {
                    LOGGER.warn("Item.getDefaultInstance() called before components are bound - " +
                        "returning EMPTY. This is expected during early mod initialization in 26.1.");
                    warned = true;
                }
                return getItemStackEmpty();
            }
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    private static Object getItemStackEmpty() {
        if (!emptyResolved) {
            try {
                Class<?> itemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
                itemStackEmpty = itemStackClass.getField("EMPTY").get(null);
            } catch (Exception e) {
                LOGGER.error("Failed to get ItemStack.EMPTY: {}", e.getMessage());
            }
            emptyResolved = true;
        }
        return itemStackEmpty;
    }

    /** No-op for a removed instance void method like Listener.setGain(float). */
    public static void noOp(Object instance, float value) {
    }

    /** No-op for a removed no-arg instance void method like VertexConsumer.endVertex(). */
    public static void noOpVoid(Object instance) {
    }

    /** No-op for a removed static void method like RenderSystem.enableBlend(). */
    public static void noOpStatic() {
    }

    // Util.backgroundExecutor() returns a single shared pool; mirror that with one lazily-created
    // daemon-threaded instance so callsites don't leak a fresh unbounded pool on every call.
    private static volatile java.util.concurrent.ExecutorService backgroundExecutor;

    /** Bridge for Util.backgroundExecutor(), removed in 26.1. */
    public static java.util.concurrent.ExecutorService getBackgroundExecutor() {
        java.util.concurrent.ExecutorService es = backgroundExecutor;
        if (es == null) {
            synchronized (ItemSafetyShim.class) {
                es = backgroundExecutor;
                if (es == null) {
                    es = java.util.concurrent.Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "Retromod-background");
                        t.setDaemon(true);
                        return t;
                    });
                    backgroundExecutor = es;
                }
            }
        }
        return es;
    }

    /** Bridge for TitleScreen.COPYRIGHT_TEXT, which went private in 26.1. */
    public static Object getTitleScreenCopyright() {
        try {
            Class<?> titleScreenClass = Class.forName("net.minecraft.client.gui.screens.TitleScreen");
            java.lang.reflect.Field field = titleScreenClass.getDeclaredField("COPYRIGHT_TEXT");
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception e) {
            try {
                Class<?> componentClass = Class.forName("net.minecraft.network.chat.Component");
                return componentClass.getMethod("empty").invoke(null);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** No-op for removed RenderSystem.setShaderColor(float, float, float, float). */
    public static void noOpColor(float r, float g, float b, float a) {
    }

    /**
     * Translation key for a 26.1 MutableComponent. class_2588 (old TranslatableText) redirects to
     * MutableComponent, but the key now lives in the component's TranslatableContents.
     */
    public static String getTranslationKey(Object component) {
        try {
            Object contents = LegacyPublicMethods.method(component.getClass(), "getContents").invoke(component);
            return (String) LegacyPublicMethods.method(contents.getClass(), "getKey").invoke(contents);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Bridge for CommandSourceStack.hasPermission(int), removed in 26.1 when permissions went from
     * int levels to a PermissionSet. Maps the old level via PermissionLevel.byId and asks the set.
     */
    public static boolean hasPermission(Object source, int level) {
        try {
            ClassLoader cl = source.getClass().getClassLoader();

            Class<?> permLevelClass = cl.loadClass("net.minecraft.server.permissions.PermissionLevel");
            Object permLevel = permLevelClass.getMethod("byId", int.class).invoke(null, level);

            Class<?> hasCommandLevelClass = cl.loadClass("net.minecraft.server.permissions.Permission$HasCommandLevel");
            Object permission = hasCommandLevelClass.getConstructor(permLevelClass).newInstance(permLevel);

            Object permSet = LegacyPublicMethods.method(source.getClass(), "permissions").invoke(source);

            Class<?> permClass = cl.loadClass("net.minecraft.server.permissions.Permission");
            return (boolean) LegacyPublicMethods.method(permSet.getClass(), "hasPermission", permClass).invoke(permSet, permission);
        } catch (Exception e) {
            return level <= 0;
        }
    }

    /**
     * Dummy Event for ItemTooltipCallback (replaces the GETSTATIC of its EVENT field). Accepts
     * register()/addPhaseOrdering() but never fires, so 3-param getTooltip lambdas don't crash.
     */
    public static Object getDummyTooltipEvent() {
        return getDummyEvent("net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback");
    }

    // Dummy Events per listener type: accept register() but never fire, dodging the
    // AbstractMethodError when an old mod registers against a changed-signature Fabric event.
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> dummyEvents =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Bridge for ScreenMouseEvents.allowMouseClick(Screen). */
    public static Object dummyAllowMouseClick(Object screen) {
        return getDummyEvent("net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents$AllowMouseClick");
    }

    /** Bridge for ScreenMouseEvents.allowMouseRelease(Screen). */
    public static Object dummyAllowMouseRelease(Object screen) {
        return getDummyEvent("net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents$AllowMouseRelease");
    }

    /** Bridge for ScreenMouseEvents.afterMouseScroll(Screen). */
    public static Object dummyAfterMouseScroll(Object screen) {
        return getDummyEvent("net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents$AfterMouseScroll");
    }

    /** Bridge for ScreenMouseEvents.beforeMouseScroll(Screen). */
    public static Object dummyBeforeMouseScroll(Object screen) {
        return getDummyEvent("net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents$BeforeMouseScroll");
    }

    private static Object getDummyEvent(String listenerClassName) {
        return dummyEvents.computeIfAbsent(listenerClassName, ItemSafetyShim::createDummyEvent);
    }

    /**
     * Event.register(listener) that swallows the ArrayStoreException thrown when an old mod registers
     * an incompatible callback on a changed Fabric event.
     */
    public static void safeEventRegister(Object event, Object listener) {
        try {
            LegacyPublicMethods.method(event.getClass(), "register", Object.class).invoke(event, listener);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ArrayStoreException) {
                LOGGER.debug("Skipped incompatible event listener: {}", listener.getClass().getName());
            } else {
                LOGGER.warn("Failed to register event listener: {}", cause.getMessage());
            }
        }
    }

    /** Phased variant of {@link #safeEventRegister}: Event.register(phase, listener). */
    public static void safeEventRegisterPhased(Object event, Object phase, Object listener) {
        try {
            for (java.lang.reflect.Method m : LegacyPublicMethods.methods(event.getClass())) {
                if (m.getName().equals("register") && m.getParameterCount() == 2
                        && !m.getParameterTypes()[0].equals(Object.class)) {
                    m.invoke(event, phase, listener);
                    return;
                }
            }
            LegacyPublicMethods.method(event.getClass(), "register", phase.getClass(), Object.class).invoke(event, phase, listener);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ArrayStoreException) {
                LOGGER.debug("Skipped incompatible phased event listener: {}", listener.getClass().getName());
            } else {
                LOGGER.warn("Failed to register phased event listener: {}", cause.getMessage());
            }
        }
    }

    /** Builds a Fabric Event that accepts registrations but never fires, via EventFactory.createArrayBacked. */
    private static Object createDummyEvent(String listenerClassName) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> listenerClass = cl.loadClass(listenerClassName);
            Class<?> eventFactory = cl.loadClass("net.fabricmc.fabric.api.event.EventFactory");

            for (Method m : eventFactory.getMethods()) {
                if (m.getName().equals("createArrayBacked") && m.getParameterCount() == 2) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params[0] == Class.class && params[1].getName().contains("Function")) {
                        // listeners -> invoker, where the invoker is a listener proxy returning true/null for any call.
                        Object invokerFunction = java.lang.reflect.Proxy.newProxyInstance(
                            cl, new Class<?>[]{ params[1] },
                            (proxy, method, args) ->
                                java.lang.reflect.Proxy.newProxyInstance(
                                    cl, new Class<?>[]{ listenerClass },
                                    (p2, m2, a2) -> {
                                        if (m2.getReturnType() == boolean.class) return true;
                                        return null;
                                    }));

                        return m.invoke(null, listenerClass, invokerFunction);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to create dummy mouse event: {}", e.getMessage());
        }
        return null;
    }
}
