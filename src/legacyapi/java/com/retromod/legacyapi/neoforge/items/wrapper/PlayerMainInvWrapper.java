/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import net.minecraft.world.entity.player.Inventory;

/** A player's 36 main slots: the hotbar and the backpack. */
public class PlayerMainInvWrapper extends RangedWrapper {

    private final Inventory inventoryPlayer;

    public PlayerMainInvWrapper(Inventory inv) {
        super(new InvWrapper(inv), 0, inv.getNonEquipmentItems().size());
        this.inventoryPlayer = inv;
    }

    public Inventory getInventoryPlayer() {
        return inventoryPlayer;
    }
}
