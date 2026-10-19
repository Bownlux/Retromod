package net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.Block;

public final class BlockState {
    private final Block block;
    public BlockState(Block block) { this.block = block; }
    public Block getBlock() { return block; }
}
