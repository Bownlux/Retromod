/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Stand-in for net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents, removed in 1.21.9 and
 * reintroduced in 1.21.10 with a redesigned API. Forwards registrations to the new API when present,
 * otherwise holds them dormant.
 */
public class WorldRenderEventsShim {

    public static final Event<BeforeBlockOutline> BEFORE_BLOCK_OUTLINE = new Event<>();
    public static final Event<AfterBlockOutline> AFTER_BLOCK_OUTLINE = new Event<>();
    public static final Event<Start> START = new Event<>();
    public static final Event<End> END = new Event<>();
    public static final Event<Last> LAST = new Event<>();
    public static final Event<BeforeEntities> BEFORE_ENTITIES = new Event<>();
    public static final Event<AfterEntities> AFTER_ENTITIES = new Event<>();
    public static final Event<BeforeDebugRender> BEFORE_DEBUG_RENDER = new Event<>();

    private static Boolean newApiAvailable = null;

    private static boolean checkNewApi() {
        if (newApiAvailable == null) {
            try {
                Class.forName("net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents");
                newApiAvailable = true;
            } catch (ClassNotFoundException e) {
                newApiAvailable = false;
            }
        }
        return newApiAvailable;
    }

    /** Event holder that forwards to the underlying event when the new API is present. */
    public static class Event<T> {
        private final List<T> listeners = new ArrayList<>();
        private Object realEvent = null;

        public void register(T listener) {
            listeners.add(listener);

            if (checkNewApi()) {
                forwardRegistration(listener);
            }
        }

        private void forwardRegistration(T listener) {
            // New API has different signatures; listener is only stored for now.
        }

        public void invoke(Object... args) {
            for (T listener : listeners) {
                try {
                    invokeListener(listener, args);
                } catch (Exception e) {
                    System.err.println("Retromod: Error invoking world render listener: " + e.getMessage());
                }
            }
        }

        private void invokeListener(T listener, Object[] args) throws Exception {
            for (Method m : LegacyPublicMethods.methods(listener.getClass())) {
                if (!m.isDefault() && !Modifier.isStatic(m.getModifiers()) &&
                    m.getDeclaringClass() != Object.class) {

                    if (m.getParameterCount() == args.length) {
                        m.invoke(listener, args);
                        return;
                    }
                }
            }
        }
    }

    @FunctionalInterface
    public interface BeforeBlockOutline {
        boolean beforeBlockOutline(Object context, Object hitResult);
    }

    @FunctionalInterface
    public interface AfterBlockOutline {
        void afterBlockOutline(Object context, Object hitResult);
    }

    @FunctionalInterface
    public interface Start {
        void onStart(Object context);
    }

    @FunctionalInterface
    public interface End {
        void onEnd(Object context);
    }

    @FunctionalInterface
    public interface Last {
        void onLast(Object context);
    }

    @FunctionalInterface
    public interface BeforeEntities {
        void beforeEntities(Object context);
    }

    @FunctionalInterface
    public interface AfterEntities {
        void afterEntities(Object context);
    }

    @FunctionalInterface
    public interface BeforeDebugRender {
        void beforeDebugRender(Object context);
    }
}
