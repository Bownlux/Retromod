/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.testmod.neoforge.FirstPersonArmMixinCase;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A first-person arm redirect in its 1.21.9 to 26.2 shape, which 26.3 moved to a split renderer (#390). */
@Mixin(ItemInHandRenderer.class)
public abstract class LegacyFirstPersonArmMixin {

    // FirstPersonArmMixinCase reads this field to see that the mixin applied.
    @Unique
    private boolean retromodTest$armRedirect;

    // 1.21.1 draws through a buffer source, so these name the submit collector shape a stand-in compiles against.
    @Shadow
    protected abstract void renderArmWithItem(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand,
            float swingProgress, ItemStack stack, float equipProgress, PoseStack poseStack, SubmitNodeCollector collector, int light);

    @Redirect(method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/player/LocalPlayer;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderArmWithItem("
                    + "Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;F"
                    + "Lnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"), require = 1)
    private void retromodTest$arm(ItemInHandRenderer renderer, AbstractClientPlayer player, float partialTick, float pitch,
            InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress, PoseStack poseStack,
            SubmitNodeCollector collector, int light) {
        retromodTest$armRedirect = true;
        FirstPersonArmMixinCase.noteDrawn();
        renderArmWithItem(player, partialTick, pitch, hand, swingProgress, stack, equipProgress, poseStack, collector, light);
    }
}
