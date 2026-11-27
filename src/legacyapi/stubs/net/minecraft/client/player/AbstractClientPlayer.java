// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.player;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;

public abstract class AbstractClientPlayer extends Player {
    public PlayerSkin getSkin() { throw new UnsupportedOperationException(); }
}
