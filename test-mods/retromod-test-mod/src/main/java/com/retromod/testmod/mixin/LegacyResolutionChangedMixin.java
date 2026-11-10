/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Load-time regression for a target the host renamed: 26.1 calls resizeDisplay() resizeGui(). */
@Mixin(MinecraftClient.class)
public abstract class LegacyResolutionChangedMixin {

    // Loom maps the Yarn name to method_15993, which Retromod reads as resizeDisplay(); the refmap
    // entry must name resizeGui() on 26.1 and newer, or Mixin finds no target.
    @Inject(method = "onResolutionChanged", at = @At("HEAD"), require = 1)
    private void retromod$beforeResolutionChanged(CallbackInfo callbackInfo) {
    }
}
