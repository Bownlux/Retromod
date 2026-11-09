// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.eventbus.api.bus;

import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraftforge.eventbus.api.event.characteristic.Cancellable;
import net.minecraftforge.eventbus.api.listener.EventListener;
import net.minecraftforge.eventbus.api.listener.ObjBooleanBiConsumer;
import net.minecraftforge.eventbus.internal.Event;

public interface CancellableEventBus<T extends Event & Cancellable> extends EventBus<T> {
    default EventListener addListener(boolean alwaysCancelling, Consumer<T> listener) { throw new UnsupportedOperationException(); }

    EventListener addListener(byte priority, boolean alwaysCancelling, Consumer<T> listener);

    EventListener addListener(Predicate<T> listener);

    EventListener addListener(byte priority, Predicate<T> listener);

    EventListener addListener(ObjBooleanBiConsumer<T> monitor);

    static <T extends Event & Cancellable> CancellableEventBus<T> create(Class<T> type) { throw new UnsupportedOperationException(); }

    static <T extends Event & Cancellable> CancellableEventBus<T> create(BusGroup group, Class<T> type) { throw new UnsupportedOperationException(); }
}
