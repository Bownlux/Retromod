/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Load-time regression for a leading parameter added to a refmap target.
 *
 * <p>The handler intentionally captures no target arguments. Loom keeps the Yarn selector in the
 * class and stores the intermediary selector in the refmap, so Retromod can prove and repair the
 * refmap change without guessing a handler layout from the source-only name.
 */
@Mixin(IntegratedServer.class)
public abstract class LegacyIntegratedServerPublishMixin {

    @Inject(
            method = "openToLan(Lnet/minecraft/world/GameMode;ZI)Z",
            at = @At("HEAD"),
            require = 1)
    private void retromod$beforePublishServer(CallbackInfoReturnable<Boolean> callbackInfo) {
    }
}
