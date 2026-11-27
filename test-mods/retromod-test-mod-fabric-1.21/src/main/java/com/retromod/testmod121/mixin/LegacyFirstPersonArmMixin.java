/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.mixin;

import net.minecraft.class_11659;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Hold My Items' first-person arm redirect in its 1.21.9 to 1.21.11 shape, which 26.3 moved to a split renderer (#390). */
@Mixin(HeldItemRenderer.class)
public abstract class LegacyFirstPersonArmMixin {

    // Test90 reads this field to see that the mixin applied.
    @Unique
    private boolean retromodTest$armRedirect;

    // 1.21.1 has no submit collector, so these name 1.21.11's intermediary members, as a mod built without a refmap does.
    @Shadow
    protected abstract void method_3228(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand,
            float swingProgress, ItemStack stack, float equipProgress, MatrixStack matrices, class_11659 collector, int light);

    @Redirect(method = "method_22976(FLnet/minecraft/class_4587;Lnet/minecraft/class_11659;Lnet/minecraft/class_746;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/class_759;method_3228(Lnet/minecraft/class_742;FFLnet/minecraft/class_1268;"
                    + "FLnet/minecraft/class_1799;FLnet/minecraft/class_4587;Lnet/minecraft/class_11659;I)V"), require = 1)
    private void retromodTest$arm(HeldItemRenderer renderer, AbstractClientPlayerEntity player, float tickProgress, float pitch,
            Hand hand, float swingProgress, ItemStack stack, float equipProgress, MatrixStack matrices, class_11659 collector,
            int light) {
        retromodTest$armRedirect = true;
        method_3228(player, tickProgress, pitch, hand, swingProgress, stack, equipProgress, matrices, collector, light);
    }
}
