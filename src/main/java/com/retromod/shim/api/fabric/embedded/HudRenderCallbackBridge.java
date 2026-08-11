/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Function;

/**
 * Runtime half of the removed Fabric {@code HudRenderCallback} bridge: 26.1 replaced
 * {@code HudRenderCallback.EVENT} with {@code HudElementRegistry}. We build an array-backed v1
 * {@code Event} for the mod's {@code EVENT.register(...)} calls and hand its combined invoker (itself
 * a {@code HudElement}, via the synthetic interface) to {@code HudElementRegistry.addLast(...)} once.
 * If the registry can't be reached we log and leave the overlays inert.
 */
public final class HudRenderCallbackBridge {

    private HudRenderCallbackBridge() {}

    private static final String TAG = "[Retromod] HudRenderCallbackBridge: ";

    private static final String EVENT_FACTORY = "net.fabricmc.fabric.api.event.EventFactory";
    private static final String REGISTRY      = "net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry";
    private static final String HUD_ELEMENT   = "net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement";
    private static final String IDENTIFIER    = "net.minecraft.resources.Identifier";
    private static final String FALLBACK_ELEMENT_PATH = "legacy_hud_render";

    /** Build the v1 {@code Event} and register its combined invoker as a HUD layer; called once from the synthetic's {@code <clinit>}. */
    public static Object installEvent(Class<?> v1Type) {
        try {
            ClassLoader cl = v1Type.getClassLoader();

            Class<?> eventFactory = Class.forName(EVENT_FACTORY, true, cl);
            Method createArrayBacked = eventFactory.getMethod("createArrayBacked", Class.class, Function.class);

            final Method sam = sam(v1Type);
            Function<Object, Object> invokerFactory = (listenersObj) -> {
                final Object[] listeners = (Object[]) listenersObj;
                return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{v1Type}, (proxy, method, args) -> {
                    // fan an interface call out to the registered v1 listeners
                    if (method.getDeclaringClass().isInterface()
                            && !"equals".equals(method.getName())
                            && !"hashCode".equals(method.getName())
                            && !"toString".equals(method.getName())) {
                        for (Object l : listeners) {
                            try {
                                sam.invoke(l, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause() != null ? e.getCause() : e;
                            }
                        }
                        return null;
                    }
                    switch (method.getName()) {
                        case "equals":   return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        default:         return "RetromodHudRenderProxy";
                    }
                });
            };
            Object event = createArrayBacked.invoke(null, v1Type, invokerFactory);

            try {
                Class<?> registry = Class.forName(REGISTRY, true, cl);
                Class<?> hudElement = Class.forName(HUD_ELEMENT, false, cl);
                Class<?> identifier = Class.forName(IDENTIFIER, false, cl);
                Object id = identifier.getMethod("fromNamespaceAndPath", String.class, String.class)
                        .invoke(null, "retromod", elementPath(v1Type));
                // invoker() via the public Event interface; ArrayBackedEvent isn't public
                Object invoker = Class.forName("net.fabricmc.fabric.api.event.Event", false, cl)
                        .getMethod("invoker").invoke(event);
                registry.getMethod("addLast", identifier, hudElement).invoke(null, id, invoker);
            } catch (Throwable t) {
                System.out.println(TAG + "could not attach to HudElementRegistry (" + t + "); "
                        + "v1 HUD callbacks will register but won't render.");
            }
            return event;
        } catch (Throwable t) {
            System.out.println(TAG + "could not create the v1 event (" + t + "); "
                    + "HudRenderCallback.EVENT will be null.");
            return null;
        }
    }

    private static Method sam(Class<?> declared) {
        for (Method m : declared.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) return m;
        }
        throw new IllegalStateException("no SAM on " + declared.getName());
    }

    /**
     * Each offline-transformed mod owns a relocated copy of the legacy callback. Fabric requires
     * every HUD element id to be unique, so include that callback identity in a valid resource path.
     */
    static String elementPath(Class<?> v1Type) {
        if (v1Type == null || v1Type.getName().isBlank()) {
            return FALLBACK_ELEMENT_PATH;
        }

        String className = v1Type.getName();
        StringBuilder safeName = new StringBuilder(className.length());
        for (int i = 0; i < className.length(); i++) {
            char c = Character.toLowerCase(className.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '_' || c == '-') {
                safeName.append(c);
            } else {
                safeName.append('_');
            }
        }

        if (safeName.length() == 0) {
            return FALLBACK_ELEMENT_PATH;
        }
        return FALLBACK_ELEMENT_PATH + "/" + safeName + "_" + stableSuffix(className);
    }

    private static String stableSuffix(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return String.format(Locale.ROOT, "%016x", hash);
    }
}
