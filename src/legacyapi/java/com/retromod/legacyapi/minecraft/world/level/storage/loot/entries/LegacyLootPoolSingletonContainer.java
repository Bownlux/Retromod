/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.storage.loot.entries;

import com.mojang.datafixers.Products;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.retromod.shim.common.embedded.LegacyLootSerializers;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.SingleEntryContainerBase;
import net.minecraft.world.level.storage.loot.entries.UniformContainerBase;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/** The single loot entry base 26.3 removed, {@code LootPoolSingletonContainer}, rebuilt over 26.3's single entry base. */
public abstract class LegacyLootPoolSingletonContainer extends SingleEntryContainerBase {

    protected final List<LootItemCondition> conditions;
    protected final List<LootItemFunction> functions;

    protected LegacyLootPoolSingletonContainer(int weight, int quality, List<LootItemCondition> conditions,
            List<LootItemFunction> functions) {
        // 26.3 holds one optional condition and one optional modifier, and expands, weighs and applies them itself.
        super(weight, quality, holder(LegacyLootSerializers.conditionHolder(conditions)),
                holder(LegacyLootSerializers.functionHolder(functions)));
        this.conditions = conditions == null ? List.of() : conditions;
        this.functions = functions == null ? List.of() : functions;
    }

    /** The old codec fields: weight, quality, and 26.3's condition and modifier, read as lists. */
    protected static <T extends LegacyLootPoolSingletonContainer> Products.P4<RecordCodecBuilder.Mu<T>, Integer, Integer,
            List<LootItemCondition>, List<LootItemFunction>> singletonFields(RecordCodecBuilder.Instance<T> instance) {
        Products.P4<RecordCodecBuilder.Mu<T>, Integer, Integer, Optional<Holder<LootItemCondition>>,
                Optional<Holder<LootItemFunction>>> fields = uniformFields(instance);
        // A mapped field still writes through the host's own getter, so a written entry keeps 26.3's shape.
        return new Products.P4<>(fields.t1(), fields.t2(),
                instance.map(LegacyLootPoolSingletonContainer::conditionList, fields.t3()),
                instance.map(LegacyLootPoolSingletonContainer::functionList, fields.t4()));
    }

    /** A builder that hands {@code constructor} the weight, quality, conditions and functions it collected. */
    public static UniformContainerBase.Builder<?> simpleBuilder(EntryConstructor constructor) {
        return new DummyBuilder(constructor);
    }

    /** Whether the conditions pass. 26.3 tests them privately before it expands the entry. */
    protected final boolean canRun(LootContext context) {
        return condition.isEmpty() || condition.get().value().test(context);
    }

    /** The entry's codec. A mod built before 26.1 names it through {@code getType()}, whose stand-in is that codec. */
    @Override
    @SuppressWarnings("unchecked")
    public MapCodec<? extends SingleEntryContainerBase> codec() {
        try {
            return (MapCodec<? extends SingleEntryContainerBase>) getClass().getMethod("getType").invoke(this);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException failure ? failure : new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException("The loot entry " + getClass().getName() + " names no codec Retromod can"
                    + " read: it overrides neither codec() nor getType(). Report it with the mod's name and version.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<Holder<T>> holder(Optional<Object> held) {
        return (Optional<Holder<T>>) (Optional<?>) held;
    }

    @SuppressWarnings("unchecked")
    static List<LootItemCondition> conditionList(Optional<Holder<LootItemCondition>> condition) {
        return (List<LootItemCondition>) (List<?>) LegacyLootSerializers.conditionList(condition);
    }

    @SuppressWarnings("unchecked")
    static List<LootItemFunction> functionList(Optional<Holder<LootItemFunction>> modifier) {
        return (List<LootItemFunction>) (List<?>) LegacyLootSerializers.functionList(modifier);
    }

    /** The old constructor reference {@link #simpleBuilder} takes. */
    @FunctionalInterface
    public interface EntryConstructor {
        UniformContainerBase build(int weight, int quality, List<LootItemCondition> conditions, List<LootItemFunction> functions);
    }

    /** The old entry builder: 26.3's, which also hands out what it collected as the old lists. */
    public abstract static class Builder<T extends Builder<T>> extends UniformContainerBase.Builder<T> {

        protected List<LootItemCondition> getConditions() {
            return conditionList(getCondition());
        }

        protected List<LootItemFunction> getFunctions() {
            return functionList(getModifier());
        }
    }

    static final class DummyBuilder extends Builder<DummyBuilder> {
        private final EntryConstructor constructor;

        DummyBuilder(EntryConstructor constructor) {
            this.constructor = constructor;
        }

        @Override
        protected DummyBuilder getThis() {
            return this;
        }

        @Override
        public LootPoolEntryContainer build() {
            return constructor.build(weight, quality, getConditions(), getFunctions());
        }
    }
}
