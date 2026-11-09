// Compile-only stub of a 1.21.2 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage.loot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.context.ContextKey;
import net.minecraft.util.context.ContextKeySet;

public class LootParams {
    public static class Builder {
        public Builder(ServerLevel level) {}
        public <T> Builder withParameter(ContextKey<T> key, T value) { throw new UnsupportedOperationException(); }
        public <T> Builder withOptionalParameter(ContextKey<T> key, T value) { throw new UnsupportedOperationException(); }
        public Builder withLuck(float luck) { throw new UnsupportedOperationException(); }
        public LootParams create(ContextKeySet params) { throw new UnsupportedOperationException(); }
    }
}
