// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

public class FMLClientSetupEvent extends Event implements IModBusEvent {
    private FMLClientSetupEvent() {}
    // Declared on ParallelDispatchEvent on the host.
    public java.util.concurrent.CompletableFuture<Void> enqueueWork(Runnable work) { throw new UnsupportedOperationException(); }
}
