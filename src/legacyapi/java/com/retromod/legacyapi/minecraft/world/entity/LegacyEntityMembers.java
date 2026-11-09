/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Entity members Minecraft removed or reshaped after 1.21.1, rebuilt on the equipment and owner APIs that replaced them. */
public final class LegacyEntityMembers {

    // Entities whose old hurt override is running on this thread, so its super call through the host returns.
    private static final ThreadLocal<Set<Object>> HURTING =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    private LegacyEntityMembers() {}

    /** Marks an entity's old hurt override as running; false when it already is, which ends the round trip. */
    public static boolean enterHurt(Object entity) {
        return HURTING.get().add(entity);
    }

    public static void exitHurt(Object entity) {
        HURTING.get().remove(entity);
    }

    /** Whether an entity's old hurt override is running on this thread. */
    public static boolean isHurting(Object entity) {
        return HURTING.get().contains(entity);
    }

    /** {@code getArmorSlots()}: the four armor stacks, feet first, as the old armor list ordered them. */
    public static Iterable<ItemStack> getArmorSlots(Object entity) {
        LivingEntity living = (LivingEntity) entity;
        return List.of(living.getItemBySlot(EquipmentSlot.FEET), living.getItemBySlot(EquipmentSlot.LEGS),
                living.getItemBySlot(EquipmentSlot.CHEST), living.getItemBySlot(EquipmentSlot.HEAD));
    }

    /** {@code LivingEntity.getSlotForHand(hand)}, static until 1.21.9: the hand's own equipment slot. */
    public static EquipmentSlot getSlotForHand(InteractionHand hand) {
        return hand.asEquipmentSlot();
    }

    /** {@code getCommandSenderWorld()}: the level the entity is in. */
    public static Level getCommandSenderWorld(Object entity) {
        return ((Entity) entity).level();
    }

    /** {@code getTags()}: the scoreboard tags, which 26.1 renamed. */
    public static Set<String> getTags(Object entity) {
        return ((Entity) entity).entityTags();
    }

    /** {@code startRiding(vehicle, force)}: mounts and fires the mount events, as the one-argument form still does. */
    public static boolean startRiding(Object entity, Entity vehicle, boolean force) {
        return ((Entity) entity).startRiding(vehicle, force, true);
    }

    /** {@code getOwnerUUID()}: the owner's id, or null when the entity has no owner. */
    public static UUID getOwnerUUID(Object entity) {
        EntityReference<LivingEntity> owner = ((OwnableEntity) entity).getOwnerReference();
        return owner == null ? null : owner.getUUID();
    }
}
