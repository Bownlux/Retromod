package fixture;

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.block.state.BlockState;

/** A host tint source with one color, like vanilla's constant sources. */
public record ConstantTint(int value) implements BlockTintSource {
    @Override public int color(BlockState state) { return value; }
}
