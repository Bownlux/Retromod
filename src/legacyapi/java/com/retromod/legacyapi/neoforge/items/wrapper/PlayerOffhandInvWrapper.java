/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import net.minecraft.world.entity.player.Inventory;

/** A player's offhand slot, which follows the armor slots. */
public class PlayerOffhandInvWrapper extends RangedWrapper {

    public PlayerOffhandInvWrapper(Inventory inv) {
        super(new InvWrapper(inv), inv.getNonEquipmentItems().size() + 4, inv.getNonEquipmentItems().size() + 5);
    }
}
