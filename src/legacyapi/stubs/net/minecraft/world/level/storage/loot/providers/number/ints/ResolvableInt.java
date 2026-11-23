// Compile-only stub of a Minecraft 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage.loot.providers.number.ints;

import net.minecraft.resources.ResourceKey;

public interface ResolvableInt {
    int get(net.minecraft.world.level.storage.loot.LootContext context, int fallback);

    final class Constant implements ResolvableInt {
        public int value() { throw new UnsupportedOperationException(); }
        @Override public int get(net.minecraft.world.level.storage.loot.LootContext context, int fallback) { throw new UnsupportedOperationException(); }
    }

    final class Reference implements ResolvableInt {
        public ResourceKey<ContextIntProvider> key() { throw new UnsupportedOperationException(); }
        @Override public int get(net.minecraft.world.level.storage.loot.LootContext context, int fallback) { throw new UnsupportedOperationException(); }
    }
}
