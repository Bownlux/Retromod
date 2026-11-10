// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.fml.event;

import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.InheritableEvent;

public interface IModBusEvent extends InheritableEvent {
    static <T extends IModBusEvent> EventBus<T> getBus(BusGroup group, Class<T> type) { throw new UnsupportedOperationException(); }
}
