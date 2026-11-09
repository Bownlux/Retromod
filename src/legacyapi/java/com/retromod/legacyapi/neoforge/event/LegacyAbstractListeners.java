/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.event;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

import java.util.function.Consumer;

/** An old {@code addListener} on an event NeoForge made abstract, added to each concrete event below it instead. */
public final class LegacyAbstractListeners {

    private LegacyAbstractListeners() {}

    public static void addListener(IEventBus bus, Consumer<?> listener, String children) {
        addListener(bus, EventPriority.NORMAL, false, listener, children);
    }

    public static void addListener(IEventBus bus, EventPriority priority, Consumer<?> listener, String children) {
        addListener(bus, priority, false, listener, children);
    }

    public static void addListener(IEventBus bus, boolean receiveCanceled, Consumer<?> listener, String children) {
        addListener(bus, EventPriority.NORMAL, receiveCanceled, listener, children);
    }

    /** Adds the listener once per child, named by Retromod when it translated the mod, so each event reaches it once. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void addListener(IEventBus bus, EventPriority priority, boolean receiveCanceled,
            Consumer<?> listener, String children) {
        // The lambda's own loader is the mod's, which sees every NeoForge event.
        ClassLoader loader = listener.getClass().getClassLoader();
        for (String child : children.split(",")) {
            Class<? extends Event> type;
            try {
                type = (Class<? extends Event>) Class.forName(child.replace('/', '.'), false, loader);
            } catch (ClassNotFoundException absent) {
                // A child this host lacks never posts here either.
                continue;
            }
            bus.addListener(priority, receiveCanceled, (Class) type, (Consumer) listener);
        }
    }
}
