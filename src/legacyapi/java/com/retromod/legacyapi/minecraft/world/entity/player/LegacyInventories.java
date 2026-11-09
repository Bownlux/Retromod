/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity.player;

import java.util.AbstractList;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** The inventory's armor and offhand lists, which 1.21.5 moved into the equipment, as live views of the inventory's own slots. */
public final class LegacyInventories {

    // The inventory slots behind the old lists: armor feet first at 36 to 39, then the offhand at 40.
    private static final int[] ARMOR = {36, 37, 38, 39};
    private static final int[] OFFHAND = {40};

    private LegacyInventories() {}

    /** {@code inventory.offhand}: a one-slot list over the inventory's offhand slot. */
    public static NonNullList<ItemStack> offhand(Object inventory) {
        return new SlotList((Inventory) inventory, OFFHAND);
    }

    /** {@code inventory.armor}: a four-slot list, feet first, over the inventory's armor slots. */
    public static NonNullList<ItemStack> armor(Object inventory) {
        return new SlotList((Inventory) inventory, ARMOR);
    }

    /** {@code inventory.getArmor(index)}: one armor stack, feet first. */
    public static ItemStack getArmor(Object inventory, int index) {
        return ((Inventory) inventory).getItem(ARMOR[index]);
    }

    /** {@code new Inventory(player)}: an inventory with its own equipment, as a copy or fake player's was. */
    public static Inventory newInventory(Player player) {
        return new Inventory(player, new EntityEquipment());
    }

    /** A NonNullList whose slots are inventory slots, so a copied inventory keeps its own stacks. */
    static final class SlotList extends NonNullList<ItemStack> {
        SlotList(Inventory inventory, int[] slots) {
            super(new SlotView(inventory, slots), ItemStack.EMPTY);
        }
    }

    private static final class SlotView extends AbstractList<ItemStack> {
        private final Inventory inventory;
        private final int[] slots;

        SlotView(Inventory inventory, int[] slots) {
            this.inventory = inventory;
            this.slots = slots;
        }

        @Override
        public ItemStack get(int index) {
            return inventory.getItem(slots[index]);
        }

        // Inventory.setItem writes the inventory's equipment directly, as the old list did, without equip events.
        @Override
        public ItemStack set(int index, ItemStack stack) {
            ItemStack previous = get(index);
            inventory.setItem(slots[index], stack);
            return previous;
        }

        @Override
        public int size() {
            return slots.length;
        }
    }
}
