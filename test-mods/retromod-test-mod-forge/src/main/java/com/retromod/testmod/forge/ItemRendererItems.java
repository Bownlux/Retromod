/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.LoggerFactory;

/** Registers the old item renderer case's item on both sides, and gives it to a joining player so the hotbar draws it. */
public final class ItemRendererItems {

    private ItemRendererItems() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(ItemRendererItems::onRegister);
            MinecraftForge.EVENT_BUS.addListener(ItemRendererItems::giveOnLogin);
        } catch (Throwable t) {
            log("The old item renderer case's item registers (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onRegister(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.ITEMS, helper -> helper.register(
                new ResourceLocation("retromod_test_mod_forge", "legacy_renderer_item"), LegacyRendererItem.INSTANCE));
    }

    // A test world's player gets one, once, so the item renderer case can see it drawn in the hotbar.
    private static void giveOnLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (inventory.getItem(slot).is(LegacyRendererItem.INSTANCE)) return;
            }
            inventory.add(new ItemStack(LegacyRendererItem.INSTANCE));
        } catch (Throwable t) {
            log("The old item renderer case's item is given at login", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
