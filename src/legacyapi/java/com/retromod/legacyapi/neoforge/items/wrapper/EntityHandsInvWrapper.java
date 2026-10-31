/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/** A living entity's main hand and offhand. */
public class EntityHandsInvWrapper extends EntityEquipmentInvWrapper {

    public EntityHandsInvWrapper(LivingEntity entity) {
        super(entity, EquipmentSlot.Type.HAND);
    }
}
