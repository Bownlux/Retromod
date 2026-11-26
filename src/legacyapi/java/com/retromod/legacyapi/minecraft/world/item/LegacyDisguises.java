/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import com.retromod.legacyapi.minecraft.LegacyOldOverrides;
import java.lang.reflect.Method;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Forge's old {@code isEnderMask} item hook, asked by the disguise hook a Forge-family 26.x host calls instead. */
public final class LegacyDisguises {

    /** {@link #oldAnswer}'s value when the item gives no answer, so the host's own disguise check decides. */
    public static final int NO_ANSWER = -1;
    // A null type is the enderman class, which 26.3 renamed from EnderMan to Enderman.
    private static final Class<?>[] OLD_SHAPE = {ItemStack.class, Player.class, null};

    private LegacyDisguises() {}

    /** The item's old answer for an enderman looking at the player: 1 for a mask, 0 for none, or {@link #NO_ANSWER}. */
    public static int oldAnswer(Object item, ItemStack stack, Player player, Object target) {
        Method own = item == null ? null : LegacyOldOverrides.find(item.getClass(), "isEnderMask", OLD_SHAPE);
        // The old hook was asked only about endermen; other mobs that react to a gaze keep the host's check.
        if (own == null || !own.getParameterTypes()[2].isInstance(target)) return NO_ANSWER;
        Object answer = LegacyOldOverrides.call(item, own, stack, player, target);
        return answer instanceof Boolean mask ? (mask ? 1 : 0) : NO_ANSWER;
    }
}
