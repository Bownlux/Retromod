/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.event;

import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;

/** NeoForge 1.21.1's default component calls, which edited a patch, over the initializers 26.1 takes instead. */
public final class LegacyDefaultComponents {

    private LegacyDefaultComponents() {}

    /** Old {@code modify(item, patch -> ...)}: the mod's edits are applied to the item's default components. */
    public static void modify(ModifyDefaultComponentsEvent event, ItemLike item, Consumer<DataComponentPatch.Builder> edits) {
        event.modify(item, (builder, registries, target) -> apply(builder, edits));
    }

    /** Old {@code modifyMatching(predicate, patch -> ...)}: the predicate sees only the item, as it did. */
    public static void modifyMatching(ModifyDefaultComponentsEvent event, Predicate<? super Item> predicate,
            Consumer<DataComponentPatch.Builder> edits) {
        event.modifyMatching((item, components) -> predicate.test(item), (builder, registries, target) -> apply(builder, edits));
    }

    /** Runs the old edits on a patch and copies what they set and removed onto the host's builder. */
    private static void apply(DataComponentMap.Builder builder, Consumer<DataComponentPatch.Builder> edits) {
        DataComponentPatch.Builder patch = DataComponentPatch.builder();
        edits.accept(patch);
        DataComponentPatch.SplitResult split = patch.build().split();
        DataComponentMap added = split.added();
        for (DataComponentType<?> type : added.keySet()) copy(builder, added, type);
        // The host's builder drops a component that is set to null.
        for (DataComponentType<?> type : split.removed()) builder.set(type, null);
    }

    private static <T> void copy(DataComponentMap.Builder builder, DataComponentMap from, DataComponentType<T> type) {
        builder.set(type, from.get(type));
    }
}
