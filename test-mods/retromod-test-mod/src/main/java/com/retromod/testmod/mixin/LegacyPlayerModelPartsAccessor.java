/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Regression for the player model-parts field moving to Avatar in 1.21.11. */
@Mixin(PlayerEntity.class)
public interface LegacyPlayerModelPartsAccessor {

    @Accessor("PLAYER_MODEL_PARTS")
    static TrackedData<Byte> retromod$getPlayerModelParts() {
        throw new AssertionError("Mixin did not replace the player model-parts accessor");
    }
}
