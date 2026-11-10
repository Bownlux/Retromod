// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public class BlockItem extends Item {
    public BlockItem(Block block, Item.Properties properties) { throw new UnsupportedOperationException(); }
    public Block getBlock() { throw new UnsupportedOperationException(); }
    public static boolean updateCustomBlockEntityTag(Level level, Player player, BlockPos pos, ItemStack stack) { throw new UnsupportedOperationException(); }
}
