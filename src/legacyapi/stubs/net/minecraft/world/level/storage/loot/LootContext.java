// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage.loot;

import net.minecraft.core.HolderGetter;

public class LootContext {
    private LootContext() {}

    public static class Builder {
        public Builder(LootParams params) { throw new UnsupportedOperationException(); }
        public Builder withOptionalRandomSource(net.minecraft.util.RandomSource random) { throw new UnsupportedOperationException(); }
        public LootContext create(java.util.Optional<net.minecraft.resources.Identifier> randomSequence) { throw new UnsupportedOperationException(); }
    }

    public HolderGetter.Provider getResolver() { throw new UnsupportedOperationException(); }
    public net.minecraft.server.level.ServerLevel getLevel() { throw new UnsupportedOperationException(); }
}
