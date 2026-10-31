package net.fabricmc.fabric.api.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;

public final class ServerLifecycleEvents {
    public static final Event<ServerStarted> SERVER_STARTED = EventFactory.createArrayBacked(ServerStarted.class,
            listeners -> server -> { for (ServerStarted listener : listeners) listener.onServerStarted(server); });

    private ServerLifecycleEvents() {}

    public interface ServerStarted {
        void onServerStarted(MinecraftServer server);
    }
}
