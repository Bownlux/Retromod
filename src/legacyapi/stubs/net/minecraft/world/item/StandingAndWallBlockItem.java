// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;

public class StandingAndWallBlockItem extends BlockItem {
    public StandingAndWallBlockItem(Block block, Block wallBlock, Direction attachmentDirection, Item.Properties properties) {
        super(block, properties);
    }
}
