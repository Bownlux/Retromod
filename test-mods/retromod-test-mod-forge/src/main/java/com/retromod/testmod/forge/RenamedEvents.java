/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Pig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.SaplingGrowTreeEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.LoggerFactory;

/**
 * Listeners on two events Forge 26.2 removed: EntityInteract, now EntityInteractSpecific, and
 * SaplingGrowTreeEvent, now BlockFeatureGrowEvent. Both fire after the summary, so the results
 * are in the log. The EntityInteract lines need a player to right-click a pig. Besides the getter
 * check, each call is counted per side and hand, so the log shows how often the host posts the
 * event for one click. Forge 1.20.1 posted it once per hand on each side; Forge 26.2 posts it once
 * per hand on the server and twice per hand on the client.
 */
final class RenamedEvents {

    // Calls logged per side and hand, enough to show two clicks.
    private static final int COUNTED_CALLS = 4;
    // The client and server threads both write these in singleplayer.
    private static final Set<Boolean> INTERACT_SIDES_CHECKED = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicInteger> INTERACT_CALLS = new ConcurrentHashMap<>();
    private static SaplingGrowTreeEvent heard;

    private RenamedEvents() {}

    static void register() {
        try {
            MinecraftForge.EVENT_BUS.addListener(RenamedEvents::onEntityInteract);
            MinecraftForge.EVENT_BUS.addListener(RenamedEvents::onSaplingGrow);
            MinecraftForge.EVENT_BUS.addListener(RenamedEvents::growOnStart);
        } catch (Throwable t) {
            log("Old EntityInteract and SaplingGrowTreeEvent listeners register (construction)",
                    "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // Every getter old mods call, and setCancellationResult with the value it already has.
    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Pig pig)) return;
        boolean client = event.getLevel().isClientSide();
        String side = client ? "client" : "server";
        if (INTERACT_SIDES_CHECKED.add(client)) {
            event.setCancellationResult(event.getCancellationResult());
            boolean read = event.getEntity() != null && event.getHand() != null && event.getItemStack() != null
                    && pig.level() == event.getLevel();
            log("An old EntityInteract listener reads a right-clicked pig (player interaction, " + side + ")",
                    read ? "success" : "fail: a getter returned the wrong value");
        }
        // A click tries each hand while nothing consumes it, so calls are counted per hand.
        String where = side + ", " + event.getHand();
        int calls = INTERACT_CALLS.computeIfAbsent(where, key -> new AtomicInteger()).incrementAndGet();
        if (calls <= COUNTED_CALLS) {
            log("Old EntityInteract listener calls so far (player interaction, " + where + ")", String.valueOf(calls));
        }
    }

    private static void onSaplingGrow(SaplingGrowTreeEvent event) {
        heard = event;
    }

    // ForgeEventFactory.blockGrowFeature builds the event, posts it, and returns it.
    private static void growOnStart(ServerStartedEvent event) {
        String result;
        try {
            ServerLevel level = event.getServer().overworld();
            heard = null;
            SaplingGrowTreeEvent grown = ForgeEventFactory.blockGrowFeature(level, level.getRandom(), BlockPos.ZERO, null);
            boolean ok = grown == heard && grown.getPos().equals(BlockPos.ZERO) && grown.getLevel() == level
                    && grown.getRandomSource() != null && grown.getFeature() == null;
            result = ok ? "success" : "fail: the listener heard " + heard + " for " + grown;
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        log("ForgeEventFactory.blockGrowFeature posts to an old SaplingGrowTreeEvent listener (server started)", result);
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
