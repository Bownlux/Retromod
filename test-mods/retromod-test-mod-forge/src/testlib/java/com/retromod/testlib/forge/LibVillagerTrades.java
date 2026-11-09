/*
 * Retromod Test Library (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testlib.forge;

import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.BasicItemListing;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * A villager trade handler and no wandering trader one, as Better Archeology ships, so this jar
 * names only one of the two trade events. A cleric sells a bottle o' enchanting for an emerald.
 */
@Mod.EventBusSubscriber(modid = "retromod_test_lib_forge")
public final class LibVillagerTrades {

    private LibVillagerTrades() {}

    @SubscribeEvent
    public static void onVillagerTrades(VillagerTradesEvent event) {
        if (event.getType() != VillagerProfession.CLERIC) return;
        event.getTrades().get(1).add(new BasicItemListing(1, new ItemStack(Items.EXPERIENCE_BOTTLE), 12, 2));
    }
}
