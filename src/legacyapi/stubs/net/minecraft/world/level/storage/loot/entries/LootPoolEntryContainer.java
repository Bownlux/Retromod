// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage.loot.entries;

import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

public abstract class LootPoolEntryContainer {
    protected final Optional<Holder<LootItemCondition>> condition;
    protected final Optional<Holder<LootItemFunction>> modifier;

    protected LootPoolEntryContainer(Optional<Holder<LootItemCondition>> condition, Optional<Holder<LootItemFunction>> modifier) {
        throw new UnsupportedOperationException();
    }

    public abstract MapCodec<? extends LootPoolEntryContainer> codec();

    public abstract static class Builder<T extends Builder<T>> {
        public Builder() { throw new UnsupportedOperationException(); }
        protected abstract T getThis();
        protected Optional<Holder<LootItemCondition>> getCondition() { throw new UnsupportedOperationException(); }
        protected Optional<Holder<LootItemFunction>> getModifier() { throw new UnsupportedOperationException(); }
        public abstract LootPoolEntryContainer build();
    }
}
