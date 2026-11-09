// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.Block;

public class BlockState {
    public Block getBlock() { throw new UnsupportedOperationException(); }
    // Declared on BlockBehaviour.BlockStateBase on the host.
    public boolean isSolid() { throw new UnsupportedOperationException(); }
    // Declared on StateHolder on the host.
    public <T extends Comparable<T>> T getValue(net.minecraft.world.level.block.state.properties.Property<T> property) { throw new UnsupportedOperationException(); }
}
