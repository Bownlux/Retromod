// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.eventbus.api.bus;

import java.util.function.Consumer;
import net.minecraftforge.eventbus.api.listener.EventListener;
import net.minecraftforge.eventbus.internal.Event;

public interface EventBus<T extends Event> {
    EventListener addListener(Consumer<T> listener);

    EventListener addListener(byte priority, Consumer<T> listener);

    EventListener addListener(EventListener listener);

    void removeListener(EventListener listener);

    boolean post(T event);

    T fire(T event);

    boolean hasListeners();

    static <E extends Event> EventBus<E> create(Class<E> type) { throw new UnsupportedOperationException(); }

    static <E extends Event> EventBus<E> create(BusGroup group, Class<E> type) { throw new UnsupportedOperationException(); }
}
