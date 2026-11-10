/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** #302: a Farmer's Delight 1.20.1 addon's knife and registry reads, against whichever Farmer's Delight the host runs. */
final class FarmersDelightAddon {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String CASE = "[Retromod-Test-Forge] #302 a Farmer's Delight 1.20.1 addon's knife and"
            + " registry reads (server started): {}";

    private FarmersDelightAddon() {}

    public static void register(IEventBus modBus) {
        // The library's types stay in FarmersDelightCalls, which only loads when the library is installed.
        if (!ModList.get().isLoaded("farmersdelight")) {
            LOG.info(CASE, "skipped: Farmer's Delight is not installed");
            return;
        }
        // The knife is built in a deferred register's supplier, as addons build theirs. On 26.x an item
        // built before its id is known leaves a half-made registry entry that stops the first resource reload.
        FarmersDelightCalls.register(modBus);
        // 26.x binds item components when a server loads its data, so the knife is read then.
        MinecraftForge.EVENT_BUS.addListener(FarmersDelightAddon::check);
    }

    private static void check(ServerStartedEvent event) {
        String result;
        try {
            result = FarmersDelightCalls.readKnifeAndEntries();
        } catch (Throwable t) {
            result = "fail: " + t;
        }
        LOG.info(CASE, result);
    }
}
