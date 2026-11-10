// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.event.entity.player;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.LogicalSide;

public abstract class PlayerInteractEvent {
    public Player getEntity() { throw new UnsupportedOperationException(); }

    public LogicalSide getSide() { throw new UnsupportedOperationException(); }

    public static final class LeftClickBlock extends PlayerInteractEvent {}
}
