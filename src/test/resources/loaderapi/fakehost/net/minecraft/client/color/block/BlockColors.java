package net.minecraft.client.color.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockColors {
    private final Map<Block, List<BlockTintSource>> sources = new HashMap<>();
    public List<BlockTintSource> getTintSources(BlockState state) { return sources.getOrDefault(state.getBlock(), List.of()); }
    public BlockTintSource getTintSource(BlockState state, int index) {
        List<BlockTintSource> list = getTintSources(state);
        return index < list.size() ? list.get(index) : null;
    }
    public void register(List<BlockTintSource> list, Block... blocks) {
        for (Block block : blocks) sources.put(block, list);
    }
}
