/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/** Arrows, stack comparison and world data saves as a 1.20.1 mod wrote them, which 1.20.5 to 1.21 reshaped. */
final class LegacyGameplayCases {

    private LegacyGameplayCases() {}

    /** An MCreator-style arrow built with the old constructors and knockback, then saved, which crashed every hit. */
    static String arrowsBuildAndSave(ServerLevel level) {
        OldArrow placed = new OldArrow(EntityType.ARROW, 0.5, 80, 0.5, level);
        Zombie shooter = EntityType.ZOMBIE.create(level);
        if (shooter == null) return "fail: no zombie to shoot from";
        OldArrow shot = new OldArrow(EntityType.ARROW, shooter, level);
        shot.setKnockback(2);
        // The host saves the arrow's pickup stack, which an empty old pickup method must not leave empty.
        CompoundTag saved = placed.saveWithoutId(new CompoundTag());
        if (!saved.contains("pickup")) return "fail: the arrow saved without its pickup rule";
        shot.saveWithoutId(new CompoundTag());
        return shot.getOwner() == shooter ? "success" : "fail: the shooter is not the arrow's owner";
    }

    /** Sophisticated Core's stack check, with a stack renamed the 1.20.1 way. */
    static boolean sameItemSameTags() {
        ItemStack named = new ItemStack(Items.DIAMOND).setHoverName(Component.literal("Retromod"));
        ItemStack plain = new ItemStack(Items.DIAMOND);
        return ItemStack.isSameItemSameTags(named, named.copy()) && !ItemStack.isSameItemSameTags(named, plain)
                && named.hasCustomHoverName();
    }

    /** A stack's first tag and tag element, written through the 1.20.1 calls, stay on the stack. */
    static boolean tagsKeepWrites() {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.getOrCreateTag().putInt("retromod", 5);
        stack.getOrCreateTagElement("display").putString("Lore", "kept");
        CompoundTag tag = stack.getTag();
        CompoundTag display = stack.getTagElement("display");
        return tag != null && tag.getInt("retromod") == 5 && display != null && "kept".equals(display.getString("Lore"));
    }

    /** Create's direct save of a mod's world data, through SavedData. */
    static boolean worldDataSaves() {
        CounterData counter = new CounterData();
        counter.count = 7;
        SavedData data = counter;
        return data.save(new CompoundTag()).getInt("count") == 7;
    }

    /** A mod arrow as MCreator generated it: the old constructors and an empty pickup stack. */
    static final class OldArrow extends AbstractArrow {
        OldArrow(EntityType<? extends AbstractArrow> type, double x, double y, double z, Level level) {
            super(type, x, y, z, level);
        }

        OldArrow(EntityType<? extends AbstractArrow> type, LivingEntity shooter, Level level) {
            super(type, shooter, level);
        }

        @Override
        protected ItemStack getPickupItem() {
            return ItemStack.EMPTY;
        }
    }

    /** World data with one counter. */
    static final class CounterData extends SavedData {
        int count;

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putInt("count", count);
            return tag;
        }
    }
}
