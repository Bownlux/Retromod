// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.event.server;

import net.minecraftforge.eventbus.api.event.InheritableEvent;
import net.minecraftforge.eventbus.api.bus.EventBus;

// A record on the host, through ServerLifecycleEvent.
public final class ServerStoppedEvent implements InheritableEvent {
    public static final EventBus<ServerStoppedEvent> BUS = null;
}
