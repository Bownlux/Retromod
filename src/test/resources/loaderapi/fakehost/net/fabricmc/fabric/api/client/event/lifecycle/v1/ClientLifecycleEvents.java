package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;

public final class ClientLifecycleEvents {
    public static final Event<ClientStarted> CLIENT_STARTED = new Event<>();

    private ClientLifecycleEvents() {}

    @FunctionalInterface
    public interface ClientStarted {
        void onClientStarted(Minecraft client);
    }
}
