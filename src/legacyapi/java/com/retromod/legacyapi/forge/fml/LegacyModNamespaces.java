/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.fml;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import java.util.function.Consumer;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.IModBusEvent;

/** The mod an old listener belongs to, named while it runs on a bus Forge 26.2 posts with no mod active. */
public final class LegacyModNamespaces {

    /** The slot's layout; a copy from a Retromod build with another layout keeps a slot of its own. */
    public static final int PROTOCOL = 1;
    /** {@code ThreadLocal<String>}: the namespace of the old listener running on this thread, shared by every copy. */
    public static final String RUNNING = "forge.fml.runningNamespace";

    private static volatile ThreadLocal<String> running;

    private LegacyModNamespaces() {}

    /**
     * The listener, made to name its mod while it runs, when a mod adds it to its mod bus for an event Forge 26.2
     * moved off the mod bus. Forge 1.20.1 posted those events with that mod active, which a library's listener
     * added on an addon's bus relied on to register under the addon's namespace.
     */
    public static <T> Consumer<T> ofListener(BusGroup group, Class<?> type, Consumer<T> listener) {
        String namespace = listener == null || type == null || group == null ? null : namespaceToKeep(group, type);
        if (namespace == null) return listener;
        ThreadLocal<String> slot = slot();
        return event -> {
            String outer = slot.get();
            slot.set(namespace);
            try {
                listener.accept(event);
            } finally {
                if (outer == null) slot.remove();
                else slot.set(outer);
            }
        };
    }

    // The constructing mod, for a listener it added to its own mod bus for an event posted elsewhere; else null.
    // A host whose bus or FML classes cannot be read leaves the listener as it is.
    private static String namespaceToKeep(BusGroup group, Class<?> type) {
        try {
            // An event still on the mod bus reaches only that mod's listeners.
            if (group == BusGroup.DEFAULT || IModBusEvent.class.isAssignableFrom(type)) return null;
            return activeNamespace();
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** The namespace of the old listener running on this thread, or null outside one. */
    public static String current() {
        return slot().get();
    }

    // FML names a mod only while it constructs, and answers "minecraft" otherwise.
    private static String activeNamespace() {
        try {
            String namespace = ModLoadingContext.get().getActiveNamespace();
            return namespace == null || namespace.equals("minecraft") ? null : namespace;
        } catch (RuntimeException | LinkageError noContext) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<String> slot() {
        ThreadLocal<String> slot = running;
        if (slot == null) running = slot = LegacySharedSlots.get(RUNNING, PROTOCOL, ThreadLocal.class, ThreadLocal::new);
        return slot;
    }
}
