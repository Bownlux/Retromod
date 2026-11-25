// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.fml.event.lifecycle;

import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.internal.Event;

public final class FMLClientSetupEvent implements Event {
    private FMLClientSetupEvent() {}
    // Declared on ParallelDispatchEvent on the host.
    public java.util.concurrent.CompletableFuture<Void> enqueueWork(Runnable work) { throw new UnsupportedOperationException(); }
    public static EventBus<FMLClientSetupEvent> getBus(BusGroup group) { throw new UnsupportedOperationException(); }
}
