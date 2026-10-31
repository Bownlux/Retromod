/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;

import java.util.Optional;

/** A custom trigger's player predicate, which 26.3 turned into a holder of a loot condition. */
final class LegacyTriggerCase {

    private LegacyTriggerCase() {}

    /** How 1.20.2 to 26.2 triggers keep their player predicate. */
    record Instance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {}

    static boolean run() {
        ContextAwarePredicate any = EntityPredicate.wrap(EntityPredicate.Builder.entity());
        JsonElement json = ContextAwarePredicate.CODEC.encodeStart(JsonOps.INSTANCE, any).getOrThrow();
        ContextAwarePredicate back = ContextAwarePredicate.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        Optional<ContextAwarePredicate> player = EntityPredicate.ADVANCEMENT_CODEC.parse(JsonOps.INSTANCE, json).result();
        SimpleCriterionTrigger.SimpleInstance instance = new Instance(Optional.of(back));
        return player.isPresent() && instance.player().orElseThrow() == back;
    }
}
