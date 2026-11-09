/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** A mob that names its own death table with getDefaultLootTable(), as Alex's Mobs and Quark do, which 1.20.5 stopped asking for. */
final class OwnLootMobCase {

    private OwnLootMobCase() {}

    /** Kills one such zombie high above spawn and looks for the emerald its own table drops instead of rotten flesh. */
    static String dropsItsOwnTable(ServerLevel level) {
        OwnLootZombie zombie = new OwnLootZombie(EntityType.ZOMBIE, level);
        zombie.setPos(0.5, 300, 0.5);
        level.addFreshEntity(zombie);
        zombie.hurt(zombie.damageSources().generic(), Float.MAX_VALUE);
        boolean emerald = false;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, zombie.getBoundingBox().inflate(4))) {
            emerald |= item.getItem().is(Items.EMERALD);
            item.discard();
        }
        if (zombie.isAlive()) return "fail: the zombie did not die";
        return emerald ? "success" : "fail: no emerald from its own table";
    }

    /** A zombie with a death table of its own. */
    static final class OwnLootZombie extends Zombie {
        OwnLootZombie(EntityType<? extends Zombie> type, Level level) {
            super(type, level);
        }

        @Override
        protected ResourceLocation getDefaultLootTable() {
            return new ResourceLocation("retromod_test_mod_forge", "entities/own_loot_zombie");
        }
    }
}
