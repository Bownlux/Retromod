// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage.loot.predicates;

import net.minecraft.advancements.predicates.StatePropertiesPredicate;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.block.Block;

// A record on the host.
public final class MatchBlock {
    public static LootItemCondition.Builder blockMatches(HolderGetter<Block> blocks, Block block) { throw new UnsupportedOperationException(); }
    public static LootItemCondition.Builder blockMatches(HolderGetter<Block> blocks, Block block,
            StatePropertiesPredicate.Builder properties) { throw new UnsupportedOperationException(); }
}
