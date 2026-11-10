/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import com.retromod.testmod.tests.OldSurfaceRuleWrapper;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.surfacebuilder.MaterialRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** TerraBlender's shape: shadow the settings' surface rule and hand the generator a wrapped one; 26.3 keeps it in a holder. */
@Mixin(ChunkGeneratorSettings.class)
public abstract class LegacySurfaceRuleSettingsMixin {

    @Shadow
    private MaterialRules.MaterialRule surfaceRule;

    @Inject(method = "surfaceRule", at = @At("HEAD"), cancellable = true)
    private void retromodTest$wrapSurfaceRule(CallbackInfoReturnable<MaterialRules.MaterialRule> cir) {
        cir.setReturnValue(OldSurfaceRuleWrapper.wrap(this.surfaceRule));
    }
}
