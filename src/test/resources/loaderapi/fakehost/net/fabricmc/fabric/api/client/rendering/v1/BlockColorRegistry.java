package net.fabricmc.fabric.api.client.rendering.v1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** The block color registry of Fabric API 0.155.3+26.1.2 and the 26.2 and 26.3 builds. */
public final class BlockColorRegistry {
    private static final Map<Block, List<BlockTintSource>> waiting = new IdentityHashMap<>();
    private static final Map<Block, BlockTintsFactory> factories = new IdentityHashMap<>();
    private static boolean initialized = true;

    private BlockColorRegistry() {}

    // Static colors wait until the client creates its block colors, and a block colored one way
    // refuses the other, in either order.
    public static void register(List<BlockTintSource> sources, Block... blocks) {
        for (Block block : blocks) {
            if (factories.containsKey(block)) {
                throw new IllegalStateException("A dynamic block color factory for the block " + block
                        + " has already been registered and as such no static usage is allowed!");
            }
        }
        if (initialized) {
            Minecraft.getInstance().getBlockColors().register(sources, blocks);
        } else {
            for (Block block : blocks) waiting.put(block, sources);
        }
    }

    public static void register(BlockTintsFactory factory, Block... blocks) {
        for (Block block : blocks) {
            boolean colored = initialized
                    ? !Minecraft.getInstance().getBlockColors().getTintSources(block.defaultBlockState()).isEmpty()
                    : waiting.containsKey(block);
            if (colored) {
                throw new IllegalStateException("A static block color provider for the block: " + block
                        + " has already been registered and as such no dynamic usage is allowed!");
            }
            factories.put(block, factory);
        }
    }

    public static BlockTintsFactory getFactory(BlockState state) {
        return factories.get(state.getBlock());
    }

    /** Fixture: the client has not created its block colors yet. */
    public static void beginStartupForTesting() {
        initialized = false;
    }

    /** Fixture: what BlockColors.createDefault hands Fabric API. */
    public static void initializeForTesting(BlockColors colors) {
        initialized = true;
        waiting.forEach((block, sources) -> colors.register(sources, block));
        waiting.clear();
    }
}
