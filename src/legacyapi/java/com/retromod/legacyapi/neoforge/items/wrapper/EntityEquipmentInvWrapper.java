/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import com.retromod.legacyapi.neoforge.items.IItemHandlerModifiable;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** The equipment slots of one type on a living entity, such as its hands or its armor. */
public abstract class EntityEquipmentInvWrapper implements IItemHandlerModifiable {

    protected final LivingEntity entity;
    protected final List<EquipmentSlot> slots;

    public EntityEquipmentInvWrapper(LivingEntity entity, EquipmentSlot.Type type) {
        this.entity = entity;
        List<EquipmentSlot> matching = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == type) matching.add(slot);
        }
        this.slots = List.copyOf(matching);
    }

    @Override
    public int getSlots() {
        return slots.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return entity.getItemBySlot(validateSlotIndex(slot));
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        EquipmentSlot equipment = validateSlotIndex(slot);
        ItemStack existing = entity.getItemBySlot(equipment);
        int room = getStackLimit(slot, stack);
        if (!existing.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(stack, existing)) return stack;
            room -= existing.getCount();
        }
        if (room <= 0) return stack;
        int moved = Math.min(room, stack.getCount());
        if (!simulate) entity.setItemSlot(equipment, stack.copyWithCount(existing.getCount() + moved));
        return moved == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) return ItemStack.EMPTY;
        EquipmentSlot equipment = validateSlotIndex(slot);
        ItemStack existing = entity.getItemBySlot(equipment);
        if (existing.isEmpty()) return ItemStack.EMPTY;
        int taken = Math.min(amount, existing.getCount());
        if (!simulate) {
            entity.setItemSlot(equipment, taken == existing.getCount() ? ItemStack.EMPTY : existing.copyWithCount(existing.getCount() - taken));
        }
        return existing.copyWithCount(taken);
    }

    @Override
    public int getSlotLimit(int slot) {
        // Armor slots hold one item; hand slots hold a full stack.
        return validateSlotIndex(slot).getType() == EquipmentSlot.Type.HAND ? 99 : 1;
    }

    protected int getStackLimit(int slot, ItemStack stack) {
        return Math.min(getSlotLimit(slot), stack.getMaxStackSize());
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        entity.setItemSlot(validateSlotIndex(slot), stack);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return true;
    }

    protected EquipmentSlot validateSlotIndex(int slot) {
        if (slot < 0 || slot >= slots.size()) {
            throw new IllegalArgumentException("Slot " + slot + " not in valid range - [0," + slots.size() + ")");
        }
        return slots.get(slot);
    }
}
