/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Load-time regression for a constructor inject whose target gained a parameter (#425). */
@Mixin(PlayerInventory.class)
public abstract class LegacyPlayerInventoryCtorMixin {

    // 1.21.5 added an EntityEquipment after the player. The handler never reads the player, so it
    // keeps only the callback, and the refmap entry must name the new constructor too.
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void retromod$afterInit(PlayerEntity player, CallbackInfo callbackInfo) {
    }
}
