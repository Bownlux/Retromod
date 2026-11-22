/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;

/** The sign text getters 26.3 replaced with one getter per slot. */
public final class LegacySignTexts {

    private LegacySignTexts() {}

    public static SignText getFrontText(SignBlockEntity sign) {
        return sign.getText(SignTextSlot.FRONT);
    }

    public static SignText getBackText(SignBlockEntity sign) {
        return sign.getText(SignTextSlot.BACK);
    }

    /** {@code sign.getText(front)}. */
    public static SignText getText(SignBlockEntity sign, boolean front) {
        return sign.getText(front ? SignTextSlot.FRONT : SignTextSlot.BACK);
    }
}
