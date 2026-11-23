/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/** Answers old Saddleable checks and calls, for vanilla's saddled mobs, which no longer implement it, and for a mod's own. */
public final class LegacySaddleables {

    // Each translated mod carries its own copy under its own package, so another mod's copy is found by name.
    private static final String NAME = "com.retromod.legacyapi.minecraft.world.entity.LegacySaddleable";

    private LegacySaddleables() {}

    /** {@code instanceof Saddleable}: a mob that can wear a saddle, or a mod's own implementation. */
    public static boolean is(Object value) {
        if (value instanceof LegacySaddleable || (value != null && implementsByName(value.getClass()))) return true;
        return value instanceof Mob mob && mob.getType().builtInRegistryHolder().is(EntityTypeTags.CAN_EQUIP_SADDLE);
    }

    /** {@code saddleable.isSaddled()}. */
    public static boolean isSaddled(Object value) {
        if (value instanceof LegacySaddleable saddleable) return saddleable.isSaddled();
        return value instanceof Mob mob && mob.isSaddled();
    }

    /** {@code saddleable.isSaddleable()}: whether a saddle can go on now, which each mob answers for its saddle slot. */
    public static boolean isSaddleable(Object value) {
        if (value instanceof LegacySaddleable saddleable) return saddleable.isSaddleable();
        return value instanceof Mob mob && is(mob) && mob.canUseSlot(EquipmentSlot.SADDLE);
    }

    /** {@code saddleable.equipSaddle(stack, source)}: a one-item copy goes in the saddle slot, which plays its own sound. */
    public static void equipSaddle(Object value, ItemStack stack, SoundSource source) {
        if (value instanceof LegacySaddleable saddleable) {
            saddleable.equipSaddle(stack, source);
        } else if (value instanceof Mob mob) {
            // A copy, so a caller that shrinks its own stack afterwards does not empty the saddle.
            mob.setItemSlot(EquipmentSlot.SADDLE, stack.copyWithCount(1));
        }
    }

    /** {@code saddleable.getSaddleSoundEvent()}. */
    public static SoundEvent getSaddleSoundEvent(Object value) {
        if (value instanceof LegacySaddleable saddleable) return saddleable.getSaddleSoundEvent();
        return SoundEvents.HORSE_SADDLE.value();
    }

    // Walks the class and its superclasses, and every interface they extend.
    private static boolean implementsByName(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> implemented : current.getInterfaces()) {
                String name = implemented.getName();
                if (name.equals(NAME) || name.endsWith("." + NAME) || implementsByName(implemented)) return true;
            }
        }
        return false;
    }
}
