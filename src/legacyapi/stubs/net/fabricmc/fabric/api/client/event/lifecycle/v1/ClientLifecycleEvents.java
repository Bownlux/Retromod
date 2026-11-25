// Compile-only stub of a Fabric API 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;

public final class ClientLifecycleEvents {
    public static final Event<ClientStarted> CLIENT_STARTED = null;
    private ClientLifecycleEvents() {}

    @FunctionalInterface
    public interface ClientStarted {
        void onClientStarted(Minecraft client);
    }
}
