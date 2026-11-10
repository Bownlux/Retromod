/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Load-time regression for a refmap entry that must follow the handler Retromod retypes. */
@Mixin(GameRenderer.class)
public abstract class LegacyGameRendererRefmapMixin {

    // 26.x render() lost all three parameters. The handler reads none, so it keeps only the
    // callback, and the refmap entry Loom wrote for the Yarn name must name render()V as well.
    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void retromod$beforeRender(float tickDelta, long startTime, boolean tick, CallbackInfo callbackInfo) {
    }
}
