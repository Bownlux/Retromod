// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;

public class ReloadableServerRegistries {
    public static class Holder {
        public LootTable getLootTable(ResourceKey<LootTable> key) { throw new UnsupportedOperationException(); }
    }
}
