/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import net.minecraft.world.entity.player.Inventory;

/** A player's four armor slots, feet first, which follow the main slots. */
public class PlayerArmorInvWrapper extends RangedWrapper {

    private final Inventory inventoryPlayer;

    public PlayerArmorInvWrapper(Inventory inv) {
        super(new InvWrapper(inv), inv.getNonEquipmentItems().size(), inv.getNonEquipmentItems().size() + 4);
        this.inventoryPlayer = inv;
    }

    public Inventory getInventoryPlayer() {
        return inventoryPlayer;
    }
}
