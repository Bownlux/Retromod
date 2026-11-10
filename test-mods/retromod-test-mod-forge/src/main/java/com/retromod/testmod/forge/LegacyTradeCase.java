/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.BasicItemListing;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trades added through Forge 1.20.1's trade events, which Forge 26.2 removed. 26.x loads trades
 * before a mod can build items, so Retromod writes them as a data pack once the server has started,
 * and they apply from the next start. The results are in the log rather than the summary.
 */
@Mod.EventBusSubscriber(modid = "retromod_test_mod_forge")
public final class LegacyTradeCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String MOD_ID = "retromod_test_mod_forge";
    private static final String LIB_ID = "retromod_test_lib_forge";

    private LegacyTradeCase() {}

    /** One static handler, as Better Archeology ships: a farmer sells a diamond for an emerald. */
    @SubscribeEvent
    public static void onVillagerTrades(VillagerTradesEvent event) {
        if (event.getType() != VillagerProfession.FARMER) return;
        event.getTrades().get(1).add(new BasicItemListing(1, new ItemStack(Items.DIAMOND), 12, 2));
    }

    static void register() {
        // A listener added on the game bus, as Cobblemon and Bookshelf add theirs.
        MinecraftForge.EVENT_BUS.addListener(LegacyTradeCase::onWandererTrades);
        // The lowest priority runs after Retromod writes the trades for this start.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, LegacyTradeCase::check);
    }

    private static void onWandererTrades(WandererTradesEvent event) {
        event.getGenericTrades().add(new BasicItemListing(2, new ItemStack(Items.DIAMOND), 4, 1));
    }

    private static void check(ServerStartedEvent event) {
        Path legacyTrades = FMLPaths.GAMEDIR.get().resolve("config").resolve("retromod").resolve("legacy-trades");
        Path data = legacyTrades.resolve("retromod_legacy_trades_" + MOD_ID).resolve("data");
        log("the farmer's emerald trade is written as a villager_trade file (server started)", () -> {
            String trade = Files.readString(data.resolve(MOD_ID + "/villager_trade/legacy/minecraft/farmer/level_1/0.json"));
            return trade.contains("\"minecraft:emerald\"") && trade.contains("\"minecraft:diamond\"");
        });
        log("the farmer's level 1 trade tag names the written trade (server started)", () ->
                Files.readString(data.resolve("minecraft/tags/villager_trade/farmer/level_1.json"))
                        .contains(MOD_ID + ":legacy/minecraft/farmer/level_1/0"));
        log("the wandering trader's emerald trade is written as a villager_trade file (server started)", () ->
                Files.readString(data.resolve(MOD_ID + "/villager_trade/legacy/minecraft/wandering_trader/common/0.json"))
                        .contains("\"minecraft:diamond\""));
        // The library names only the villager event, so its jar carries no wandering trader event.
        Path library = legacyTrades.resolve("retromod_legacy_trades_" + LIB_ID).resolve("data");
        log("a library with only a villager trade handler writes its cleric trade (server started)", () ->
                Files.readString(library.resolve(LIB_ID + "/villager_trade/legacy/minecraft/cleric/level_1/0.json"))
                        .contains("\"minecraft:experience_bottle\""));
    }

    private static void log(String description, Check check) {
        String result;
        try {
            if (!hostBuildsTradesFromData()) {
                result = "skipped: this host still posts the trade events itself";
            } else {
                result = check.run() ? "success" : "fail: assertion was false";
            }
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] {}: {}", description, result);
    }

    /** Whether trades are data on this host (26.1 and newer), where Retromod writes them; probed without initializing. */
    private static boolean hostBuildsTradesFromData() {
        try {
            Class.forName("net.minecraft.world.item.trading.TradeSet", false, LegacyTradeCase.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean run() throws Throwable;
    }
}
