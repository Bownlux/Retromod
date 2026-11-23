/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.advancements;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.predicates.DataComponentPredicate;
import net.minecraft.world.item.ItemStack;

/** The item predicate a mod built before 1.21.5 implements, on top of the component predicate that replaced it. */
public interface LegacyItemSubPredicate extends DataComponentPredicate {

    /** The test the mod wrote, against an item stack. */
    boolean matches(ItemStack stack);

    /** The host asks with any component holder; only an item stack can answer the old test, so anything else fails it. */
    @Override
    default boolean matches(DataComponentGetter components) {
        return components instanceof ItemStack stack && matches(stack);
    }

    /** {@code new ItemSubPredicate.Type(codec)}: the old record of one codec, as the host's own concrete type. */
    static <T extends DataComponentPredicate> DataComponentPredicate.Type<T> type(Codec<T> codec) {
        return new DataComponentPredicate.ConcreteType<>(codec);
    }
}
