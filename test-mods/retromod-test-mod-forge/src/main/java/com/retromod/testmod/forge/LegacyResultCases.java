/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.BonemealEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.SaplingGrowTreeEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.Event;
import org.slf4j.LoggerFactory;

/**
 * Results on Forge events. Forge 26.2 replaced Event.Result with an enum of its own and dropped the
 * result from some events, such as EntityMountEvent, and it stops a block break on a DENY result, not a
 * cancel. The sapling and break cases post once the server starts, so their lines are in the log.
 */
final class LegacyResultCases {

    // Set only while a case posts, so real saplings keep growing and real blocks keep breaking.
    private static boolean denySaplings;
    private static boolean cancelBreaks;

    private LegacyResultCases() {}

    static void register() {
        try {
            MinecraftForge.EVENT_BUS.addListener(LegacyResultCases::onSaplingGrow);
            MinecraftForge.EVENT_BUS.addListener(LegacyResultCases::saplingOnStart);
            MinecraftForge.EVENT_BUS.addListener(LegacyResultCases::onBreak);
            MinecraftForge.EVENT_BUS.addListener(LegacyResultCases::breakOnStart);
        } catch (Throwable t) {
            log("Old SaplingGrowTreeEvent result listeners register (construction)",
                    "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** BonemealEvent kept its result on Forge 26.2: ALLOW reads back, and the event reports a result. */
    static boolean bonemealAllow() {
        BonemealEvent event = new BonemealEvent(null, null, null, null, ItemStack.EMPTY);
        event.setResult(Event.Result.ALLOW);
        return event.getResult() == Event.Result.ALLOW && event.hasResult();
    }

    /** CropGrowEvent.Pre became a record holding its result. */
    static boolean cropGrowDeny() {
        BlockEvent.CropGrowEvent.Pre event = new BlockEvent.CropGrowEvent.Pre(null, null, null);
        event.setResult(Event.Result.DENY);
        return event.getResult() == Event.Result.DENY;
    }

    /** EntityMountEvent lost its result and became a record, so two equal events must still keep their own. */
    static boolean mountResultsStaySeparate() {
        EntityMountEvent denied = new EntityMountEvent(null, null, null, true);
        EntityMountEvent other = new EntityMountEvent(null, null, null, true);
        denied.setResult(Event.Result.DENY);
        return denied.getResult() == Event.Result.DENY && other.getResult() == Event.Result.DEFAULT
                && !denied.hasResult();
    }

    private static void onSaplingGrow(SaplingGrowTreeEvent event) {
        if (denySaplings) event.setResult(Event.Result.DENY);
    }

    // ForgeEventFactory.blockGrowFeature posts the event and returns it with the listeners' result.
    private static void saplingOnStart(ServerStartedEvent event) {
        String result;
        try {
            ServerLevel level = event.getServer().overworld();
            SaplingGrowTreeEvent grown;
            denySaplings = true;
            try {
                grown = ForgeEventFactory.blockGrowFeature(level, level.getRandom(), BlockPos.ZERO, null);
            } finally {
                denySaplings = false;
            }
            result = grown.getResult() == Event.Result.DENY ? "success" : "fail: the result read back " + grown.getResult();
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        log("A SaplingGrowTreeEvent listener's DENY reads back from ForgeEventFactory.blockGrowFeature (server started)",
                result);
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (cancelBreaks) event.setCanceled(true);
    }

    // ForgeHooks.onBlockBreakEvent answers -1 for a break a listener stopped, as a claim mod stops one.
    private static void breakOnStart(ServerStartedEvent event) {
        String result;
        try {
            ServerLevel level = event.getServer().overworld();
            FakePlayer player = FakePlayerFactory.getMinecraft(level);
            BlockPos pos = BlockPos.ZERO;
            int allowed = ForgeHooks.onBlockBreakEvent(level, GameType.SURVIVAL, player, pos);
            int cancelled;
            cancelBreaks = true;
            try {
                cancelled = ForgeHooks.onBlockBreakEvent(level, GameType.SURVIVAL, player, pos);
            } finally {
                cancelBreaks = false;
            }
            if (allowed == -1) {
                result = "skipped: the server already refuses this break, for example under spawn protection";
            } else {
                result = cancelled == -1 ? "success" : "fail: the break went ahead with " + cancelled + " experience";
            }
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        log("An old listener's BreakEvent.setCanceled(true) stops the break (server started)", result);
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
