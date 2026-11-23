/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.shim.common.embedded.MultiBufferSource;
import net.minecraft.world.item.ItemStack;

/** Fabric API's older BuiltinItemRenderer, deprecated before 1.20.1, whose renderer is not told the display context. */
@FunctionalInterface
public interface LegacyBuiltinItemRenderer {
    void render(ItemStack stack, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay);
}
