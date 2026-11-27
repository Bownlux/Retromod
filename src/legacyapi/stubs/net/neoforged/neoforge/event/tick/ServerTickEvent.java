// Compile-only stub of a NeoForge 1.20.5 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.event.tick;

import net.neoforged.bus.api.Event;

public abstract class ServerTickEvent extends Event {
    public static class Pre extends ServerTickEvent {}

    public static class Post extends ServerTickEvent {}
}
