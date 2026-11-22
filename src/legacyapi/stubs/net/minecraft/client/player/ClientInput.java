// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.player;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

public class ClientInput {
    public Input keyPresses;
    protected Vec2 moveVector;
    public void tick() { throw new UnsupportedOperationException(); }
    public Vec2 getMoveVector() { throw new UnsupportedOperationException(); }
}
