/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.shim.common.embedded.MultiBufferSource;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Minecraft 1.20.1's BlockEntityWithoutLevelRenderer, the base of an old mod's item renderer, which Minecraft 1.21.4 removed. */
public class LegacyItemRenderer implements ResourceManagerReloadListener {

    public LegacyItemRenderer() {}

    // The old constructor took the game's block entity renderers and entity models, which only vanilla's own items used.
    public LegacyItemRenderer(BlockEntityRenderDispatcher blockEntityRenderDispatcher, EntityModelSet entityModelSet) {
        this();
    }

    /** Draws the stack; the old base drew only vanilla's chests, banners and the like, so this one draws nothing. */
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack, MultiBufferSource bufferSource,
            int packedLight, int packedOverlay) {
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
    }
}
