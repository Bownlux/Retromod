// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage.loot;

import java.util.function.Consumer;
import net.minecraft.world.item.ItemStack;

public class LootTable {
    public void getRandomItemsRaw(LootContext context, Consumer<ItemStack> output) { throw new UnsupportedOperationException(); }
    public void getRandomItems(LootParams params, long seed, Consumer<ItemStack> output) { throw new UnsupportedOperationException(); }
}
