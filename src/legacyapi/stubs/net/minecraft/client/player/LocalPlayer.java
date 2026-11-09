// Compile-only stub of a 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.player;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.player.Player;

// AbstractClientPlayer sits between this and Player on the host.
public class LocalPlayer extends Player {
    public ClientPacketListener connection;
    public PlayerRideableJumping jumpableVehicle() { throw new UnsupportedOperationException(); }
}
