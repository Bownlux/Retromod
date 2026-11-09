// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.eventbus.api.listener;

import net.minecraftforge.eventbus.internal.Event;

public interface EventListener {
    Class<? extends Event> eventType();

    byte priority();

    default boolean alwaysCancelling() { throw new UnsupportedOperationException(); }
}
