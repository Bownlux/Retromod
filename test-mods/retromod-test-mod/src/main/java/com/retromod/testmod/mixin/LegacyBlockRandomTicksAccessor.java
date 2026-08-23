/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Regression for the Block random-tick field moving into BlockBehaviour. */
@Pseudo
@Mixin(targets = "net.minecraft.class_2248", remap = false)
public interface LegacyBlockRandomTicksAccessor {

    @Accessor
    void setRandomTicks(boolean enabled);
}
