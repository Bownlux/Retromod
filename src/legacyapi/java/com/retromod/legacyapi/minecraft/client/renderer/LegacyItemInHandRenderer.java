/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.shim.common.embedded.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Minecraft 1.21.1's ItemInHandRenderer.renderItem, which draws a held stack through the rebuilt item renderer; 26.3 removed the class. */
public class LegacyItemInHandRenderer {

    // Mods keep the renderer from their constructors on, so one object answers every getter.
    private static final LegacyItemInHandRenderer INSTANCE = new LegacyItemInHandRenderer();

    protected LegacyItemInHandRenderer() {}

    /** {@code getItemInHandRenderer()} on a host without the class: the one stand-in, at any time. */
    public static LegacyItemInHandRenderer get(Object owner) {
        return INSTANCE;
    }

    /** Draws {@code stack} as {@code entity} holds it, inside the old draw that handed out {@code buffers}. */
    public void renderItem(LivingEntity entity, ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poseStack,
            MultiBufferSource buffers, int light) {
        draw(entity, stack, context, leftHand, poseStack, buffers, light);
    }

    /** The old {@code renderItem} called on a host's own renderer, whose draw takes a submit collector instead. */
    public static void renderItem(Object renderer, LivingEntity entity, ItemStack stack, ItemDisplayContext context, boolean leftHand,
            PoseStack poseStack, MultiBufferSource buffers, int light) {
        draw(entity, stack, context, leftHand, poseStack, buffers, light);
    }

    // The entity id seeds the model as the old renderer did, so animated held items keep their phase.
    private static void draw(LivingEntity entity, ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poseStack,
            MultiBufferSource buffers, int light) {
        LegacyItemStackRenderer.get(null).renderStatic(entity, stack, context, leftHand, poseStack, buffers,
                entity == null ? null : entity.level(), light, OverlayTexture.NO_OVERLAY, entity == null ? 0 : entity.getId());
    }
}
