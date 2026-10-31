/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common.loot;

import com.mojang.datafixers.Products;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.storage.loot.predicates.AllOfCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.LootModifier;

/** NeoForge 1.21.1's {@code LootModifier} shape, a condition array, over 26.3's single optional condition. */
public abstract class LegacyLootModifier extends LootModifier {

    /** The priority 26.3 gives a modifier whose data names none. */
    private static final int DEFAULT_PRIORITY = 1000;

    protected final LootItemCondition[] conditions;

    protected LegacyLootModifier(LootItemCondition[] conditions) {
        super(toCondition(conditions), DEFAULT_PRIORITY);
        this.conditions = conditions;
    }

    /** The old codec start: the conditions as an array, read from the {@code condition} 26.3 data carries. */
    public static <T extends LegacyLootModifier> Products.P1<RecordCodecBuilder.Mu<T>, LootItemCondition[]> codecStart(
            RecordCodecBuilder.Instance<T> instance) {
        return instance.group(LootItemCondition.CODEC.optionalFieldOf("condition")
                .xmap(LegacyLootModifier::toArray, LegacyLootModifier::toCondition)
                .forGetter(modifier -> modifier.conditions));
    }

    private static LootItemCondition[] toArray(Optional<Holder<LootItemCondition>> condition) {
        return condition.map(holder -> new LootItemCondition[]{holder.value()}).orElseGet(() -> new LootItemCondition[0]);
    }

    /** No condition, the one condition, or every condition that must all pass, as the old array was tested. */
    private static Optional<Holder<LootItemCondition>> toCondition(LootItemCondition[] conditions) {
        if (conditions == null || conditions.length == 0) return Optional.empty();
        if (conditions.length == 1) return Optional.of(Holder.direct(conditions[0]));
        List<Holder<LootItemCondition>> holders = new ArrayList<>();
        for (LootItemCondition condition : conditions) holders.add(Holder.direct(condition));
        LootItemCondition all = AllOfCondition.allOf(HolderSet.direct(holders));
        return Optional.of(Holder.direct(all));
    }
}
