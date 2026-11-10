// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.bus.api;

import java.util.function.Consumer;

public interface IEventBus {
    <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> eventType,
            Consumer<T> consumer);
    <T extends Event> void addListener(EventPriority priority, Class<T> eventType, Consumer<T> consumer);
    <T extends Event> T post(EventPriority phase, T event);
    <T extends Event> T post(T event);
}
