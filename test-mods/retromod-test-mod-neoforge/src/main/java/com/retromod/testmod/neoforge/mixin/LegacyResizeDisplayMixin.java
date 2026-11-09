/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge.mixin;

import com.retromod.testmod.neoforge.RenamedMixinTargetCase;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mixin on a method the host renamed: 26.1 calls resizeDisplay() resizeGui(). */
@Mixin(Minecraft.class)
public abstract class LegacyResizeDisplayMixin {

    // Retromod has to point the selector at resizeGui() on 26.1 and newer, or the handler never runs.
    @Inject(method = "resizeDisplay", at = @At("HEAD"), require = 1)
    private void retromod$beforeResizeDisplay(CallbackInfo callbackInfo) {
        RenamedMixinTargetCase.noteRan();
    }
}
