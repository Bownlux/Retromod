// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage.loot.entries;

import com.mojang.datafixers.Products;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

public abstract class UniformContainerBase extends LootPoolEntryContainer {
    // Blank, so javac reads the host's fields instead of folding a constant.
    protected final int weight;
    protected final int quality;

    protected UniformContainerBase(int weight, int quality, Optional<Holder<LootItemCondition>> condition,
            Optional<Holder<LootItemFunction>> modifier) {
        super(condition, modifier);
        throw new UnsupportedOperationException();
    }

    protected static <T extends UniformContainerBase> Products.P4<RecordCodecBuilder.Mu<T>, Integer, Integer,
            Optional<Holder<LootItemCondition>>, Optional<Holder<LootItemFunction>>> uniformFields(RecordCodecBuilder.Instance<T> instance) {
        throw new UnsupportedOperationException();
    }

    public static Builder<?> simpleBuilder(EntryConstructor constructor) { throw new UnsupportedOperationException(); }

    @FunctionalInterface
    public interface EntryConstructor {
        UniformContainerBase build(int weight, int quality, Optional<Holder<LootItemCondition>> condition,
                Optional<Holder<LootItemFunction>> modifier);
    }

    public abstract static class Builder<T extends Builder<T>> extends LootPoolEntryContainer.Builder<T> {
        protected int weight;
        protected int quality;
    }
}
