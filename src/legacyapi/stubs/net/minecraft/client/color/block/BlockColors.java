// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package net.minecraft.client.color.block;

import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class BlockColors {
    public BlockColors() {}
    public List<BlockTintSource> getTintSources(BlockState state) { throw new UnsupportedOperationException(); }
    public BlockTintSource getTintSource(BlockState state, int index) { throw new UnsupportedOperationException(); }
    public void register(List<BlockTintSource> sources, Block... blocks) { throw new UnsupportedOperationException(); }
}
