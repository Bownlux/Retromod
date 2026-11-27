// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.event.entity.player;

import net.minecraft.server.level.ServerPlayer;

// A mutable event on 26.2 and a record on 26.3, a class either way.
public final class PlayerSetSpawnEvent {
    public ServerPlayer.RespawnConfig getConfig() { throw new UnsupportedOperationException(); }
}
