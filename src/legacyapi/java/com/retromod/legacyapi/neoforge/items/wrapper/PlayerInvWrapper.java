/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import net.minecraft.world.entity.player.Inventory;

/** A player's main, armor, and offhand slots as one handler. */
public class PlayerInvWrapper extends CombinedInvWrapper {

    public PlayerInvWrapper(Inventory inv) {
        super(new PlayerMainInvWrapper(inv), new PlayerArmorInvWrapper(inv), new PlayerOffhandInvWrapper(inv));
    }
}
