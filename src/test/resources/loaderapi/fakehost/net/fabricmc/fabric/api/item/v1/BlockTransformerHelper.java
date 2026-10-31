package net.fabricmc.fabric.api.item.v1;

import fixture.Recorder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

// Fabric API 26.3's builder for the axe, hoe and shovel transforms; the fake records each call.
public final class BlockTransformerHelper {
    private BlockTransformerHelper() {}
    public static void registerStripping(Block input, Block stripped) { Recorder.note("strip " + input + " -> " + stripped); }
    public static void registerTilling(Block input, BlockState tilled) { Recorder.note("till " + input + " -> " + tilled.getBlock()); }
    public static void registerFlattening(Block input, BlockState flattened) { Recorder.note("flatten " + input + " -> " + flattened.getBlock()); }
}
