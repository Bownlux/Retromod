/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Bridges the removed NeoForge {@code AddReloadListenerEvent.addListener(PreparableReloadListener)}
 * onto 26.2's {@code AddServerReloadListenersEvent.addListener(Identifier, PreparableReloadListener)}
 * (#139, Legendary Survival Overhaul). Around 1.21.10/26.x NeoForge renamed the event
 * ({@code AddReloadListenerEvent} -> {@code AddServerReloadListenersEvent}) AND made the listener
 * registration require a {@code ResourceLocation}/{@code Identifier} id (inherited from
 * {@code SortedReloadListenerEvent}). A plain class-redirect fixes the {@code NoClassDefFoundError}
 * and retargets the {@code @SubscribeEvent} parameter, but the old one-arg {@code addListener} call
 * would then hit {@code NoSuchMethodError} when the event fires. This shim (the devirtualized target
 * of that call) synthesizes a deterministic id and forwards to the two-arg method, so the reload
 * listener actually registers. Soft-fails to inert if anything is unreachable.
 */
public final class ReloadListenerEventShim {

    private ReloadListenerEventShim() {}

    /** Devirtualized bridge: {@code event.addListener(listener)} -> {@code event.addListener(id, listener)}. */
    public static void addListener(Object event, Object listener) {
        if (event == null || listener == null) return;
        try {
            ClassLoader cl = event.getClass().getClassLoader();
            // 26.2 renamed ResourceLocation -> Identifier; accept either.
            Class<?> idClass = loadFirst(cl,
                    "net.minecraft.resources.Identifier",
                    "net.minecraft.resources.ResourceLocation");
            String path = ("reload_" + listener.getClass().getName())
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9/._-]", "_");
            Object id = idClass.getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, "retromod", path);

            Method two = twoArgAddListener(event.getClass());
            if (two != null) {
                two.invoke(event, id, listener);
            } else {
                System.out.println("[Retromod] ReloadListenerEventShim: no two-arg addListener on "
                        + event.getClass().getName() + "; reload listener not registered.");
            }
        } catch (Throwable t) {
            System.out.println("[Retromod] ReloadListenerEventShim: could not bridge addListener ("
                    + t + "); reload listener not registered.");
        }
    }

    private static Class<?> loadFirst(ClassLoader cl, String... names) throws ClassNotFoundException {
        for (String n : names) {
            try {
                return Class.forName(n, false, cl);
            } catch (Throwable ignore) {
                // try next
            }
        }
        throw new ClassNotFoundException(names.length > 0 ? names[0] : "none");
    }

    private static Method twoArgAddListener(Class<?> eventClass) {
        for (Method m : LegacyPublicMethods.methods(eventClass)) {
            if ("addListener".equals(m.getName()) && m.getParameterCount() == 2) {
                return m;
            }
        }
        return null;
    }
}
