/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Load-time regression for a shared handler that omits a trailing target parameter.
 *
 * <p>The selector strings intentionally use intermediary names, matching a distributed Fabric
 * mod. Both method IDs exist in the old source target. On 26.2 only the second void target remains,
 * and its unused {@code int} must be inserted before {@link CallbackInfo}.
 */
@Mixin(SoundSystem.class)
public abstract class LegacySoundSystemPrefixCaptureMixin {

    @Inject(
            method = {
                "method_4854(Lnet/minecraft/class_1113;)V",
                "method_4852(Lnet/minecraft/class_1113;I)V"
            },
            at = @At("HEAD"),
            require = 1)
    private void retromod$beforePlay(SoundInstance sound, CallbackInfo callbackInfo) {
    }
}
