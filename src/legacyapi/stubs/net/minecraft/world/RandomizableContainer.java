// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;

public interface RandomizableContainer extends Container {
    ResourceKey<LootTable> getLootTable();
    default void setLootTable(ResourceKey<LootTable> table, long seed) { throw new UnsupportedOperationException(); }
    long getLootTableSeed();
}
