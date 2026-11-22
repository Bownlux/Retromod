/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.shim.common.embedded.MultiBufferSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/** Fabric API 1.21.3's BuiltinItemRendererRegistry, which Fabric API removed with builtin item models in 1.21.4. */
public interface LegacyBuiltinItemRendererRegistry {

    /** The registry of this mod; a renderer registered here draws its item wherever the item is shown. */
    LegacyBuiltinItemRendererRegistry INSTANCE = LegacyBuiltinItemRenderers.INSTANCE;

    /** The deprecated form for an item. */
    void register(Item item, LegacyBuiltinItemRenderer renderer);

    /** The deprecated form, whose renderer was not told the display context. */
    void register(ItemLike item, LegacyBuiltinItemRenderer renderer);

    /** Draws {@code item} with {@code renderer}; a second renderer for one item is refused, as Fabric API refused it. */
    void register(ItemLike item, DynamicItemRenderer renderer);

    /** The renderer registered for {@code item}, or null. */
    DynamicItemRenderer get(ItemLike item);

    /** Draws one item stack, as a builtin item model did. */
    @FunctionalInterface
    interface DynamicItemRenderer {
        void render(ItemStack stack, ItemDisplayContext mode, PoseStack matrices, MultiBufferSource vertexConsumers, int light,
                int overlay);
    }
}
